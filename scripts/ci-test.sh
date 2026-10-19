#!/usr/bin/env bash
# CI 에서 테스트를 Docker 컨테이너 안에서 실행한다. 러너(운영 서버)에는 Java 21 만 있고 Node 가 없어서,
# 프로젝트가 요구하는 Java 25 / Node 24 를 컨테이너로 가져온다. 로컬에서도 같은 방식으로 실행할 수 있다.
#
#   scripts/ci-test.sh backend       # CI 용: sdk 전체 + H2 에서만 의미 있는 auth/bff 시험 + 실제 PostgreSQL 위의 guard/auth/bff 전체(한 컨테이너)
#   scripts/ci-test.sh h2            # 위의 H2 단계만(sdk + H2 전용 시험)
#   scripts/ci-test.sh pg            # 위의 PostgreSQL 단계만: guard, auth, bff 전체를 실제 PostgreSQL 위에서 (Flyway + ddl-auto=validate)
#   scripts/ci-test.sh backend-full  # auth, guard, sdk, bff 전체를 H2 로(./gradlew test) — 로컬에서 PostgreSQL 없이 전부 돌려 볼 때
#   scripts/ci-test.sh web       # 웹 의존성 설치 + vitest + 타입 검사 (npm ci / npm test / tsc -b)
#   scripts/ci-test.sh mobile    # 포털 모바일 레이아웃 회귀 테스트(실제 Chromium, Playwright 공식 이미지). 폰 4가지 크기에서 가로 넘침·터치 영역·입력 글꼴 측정
#
# - 저장소는 읽기 전용으로 마운트하고 컨테이너 안의 임시 디렉터리로 복사해서 빌드한다. 호스트 작업 디렉터리에
#   build/, node_modules 가 남지 않는다(배포 단계의 docker 빌드 컨텍스트와 actions/checkout 청소를 방해하지 않음).
# - 같은 호스트에서 운영 서비스가 돌아갈 수 있으므로 셀프호스티드에서는 CPU/메모리를 제한한다 (기본 2 CPU·4GB, CI_CPUS, CI_MEMORY 로 조정). GitHub 호스팅 러너는 전용 VM 이라 CPU 전부·10GB.
# - Gradle/npm 캐시는 $CI_CACHE_DIR(기본 ~/.cache/doro-ci)에 남겨 두 번째 실행부터 빠르다.
set -Eeuo pipefail

TARGET="${1:-}"
ROOT="${CI_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
CACHE="${CI_CACHE_DIR:-$HOME/.cache/doro-ci}"
# amazoncorretto 이미지에는 gradlew 가 쓰는 xargs/tar 가 없어서 Gradle 공식 이미지(Java 25)를 쓴다. Gradle 버전은 gradle-wrapper 와 맞춘다.
JAVA_IMAGE="${CI_JAVA_IMAGE:-gradle:9.5.1-jdk25}"
NODE_IMAGE="${CI_NODE_IMAGE:-node:24-alpine}"
# 버전은 web/package.json 의 @playwright/test 와 같아야 한다(이미지에 들어 있는 브라우저와 맞아야 한다).
PLAYWRIGHT_IMAGE="${CI_PLAYWRIGHT_IMAGE:-mcr.microsoft.com/playwright:v1.64.0-noble}"

mkdir -p "$CACHE/gradle" "$CACHE/npm"
# GitHub 호스팅 러너(RUNNER_ENVIRONMENT=github-hosted)는 이 작업 전용 VM(4 vCPU/16GB)이라 운영 서비스를 걱정할 필요가 없어 CPU 를 모두, 메모리를 10GB 까지 쓴다.
# 운영 서버와 같은 호스트일 수 있는 셀프호스티드는 기존 제한(2 CPU, 4GB)을 유지한다. CI_CPUS/CI_MEMORY 로 둘 다 덮어쓸 수 있다.
if [ "${RUNNER_ENVIRONMENT:-}" = github-hosted ]; then
  DEFAULT_CPUS="$(nproc 2>/dev/null || echo 2)"; DEFAULT_MEMORY=10g
else
  DEFAULT_CPUS=2; DEFAULT_MEMORY=4g
fi
LIMITS=(--cpus "${CI_CPUS:-$DEFAULT_CPUS}" --memory "${CI_MEMORY:-$DEFAULT_MEMORY}")
# 호스트 사용자로 실행해 캐시/산출물이 root 소유가 되지 않게 한다.
COMMON=(--rm "${LIMITS[@]}" --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$CACHE:/cache")

case "$TARGET" in
  h2|backend-full)
    # h2: sdk 전체 + H2 에서만 의미 있는 auth/bff 시험. backend-full: auth, guard, sdk, bff 전체를 H2 로(로컬에서 PostgreSQL 없이 전부 돌려 볼 때).
    if [ "$TARGET" = h2 ]; then GRADLE_TASKS=":sdk:test :auth:testH2Only :bff:testH2Only"; else GRADLE_TASKS="test"; fi
    docker run "${COMMON[@]}" -e GRADLE_USER_HOME=/cache/gradle -e GRADLE_TASKS="$GRADLE_TASKS" -v "$ROOT:/src:ro" "$JAVA_IMAGE" sh -ec '
      # 읽기 전용 소스를 복사한 뒤 산출물/캐시 디렉터리를 지운다 .
      cp -R /src /tmp/work
      cd /tmp/work
      rm -rf .git build auth/build guard/build sdk/build bff/build web/node_modules
      ./gradlew $GRADLE_TASKS --no-daemon --console=plain
    '
    ;;
  pg|backend)
    # 일회용 PostgreSQL 을 같은 도커 네트워크에 띄우고, 그 위에서 마이그레이션·엔티티·제약·동시성을 검증한다.
    # (H2 + create-drop 은 PostgreSQL 전용 SQL, FK, 부분 유니크 인덱스, 엔티티-스키마 불일치를 검증하지 못한다)
    # backend(CI)는 같은 컨테이너에서 H2 단계(sdk + H2 전용 시험)를 먼저 돌리고 이어서 PostgreSQL 단계를 돌린다. 소스 복사와 컴파일을
    # 한 번만 하고, auth/guard/bff 의 나머지 시험은 PostgreSQL 단계가 수행하므로 H2 로 되풀이하지 않는다.
    if [ "$TARGET" = backend ]; then H2_TASKS=":sdk:test :auth:testH2Only :bff:testH2Only"; else H2_TASKS=""; fi
    PG_TASKS=":guard:testOnPostgres :auth:testOnPostgres :bff:testOnPostgres"
    PG_NAME="ci-pg-$$"; PG_NET="ci-net-$$"; PG_PASSWORD="ci-only-$$"
    cleanup_pg() { docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; docker network rm "$PG_NET" >/dev/null 2>&1 || true; }
    trap cleanup_pg EXIT
    docker network create "$PG_NET" >/dev/null
    docker run -d --name "$PG_NAME" --network "$PG_NET" -e POSTGRES_PASSWORD="$PG_PASSWORD" postgres:16-alpine >/dev/null
    # pg_isready(유닉스 소켓)는 이미지가 초기화하는 동안 잠깐 뜨는 임시 서버에도 성공한다. 그 서버는 곧 내려가고 본 서버가 다시 뜨므로, 그 사이에 스키마를
    # 만들면 "No such file or directory" 로 실패한다(GitHub 호스팅 러너에서 실제로 났다). 임시 서버는 TCP 를 열지 않으므로 TCP(127.0.0.1)로 질의가 되는지로 기다린다.
    for _ in $(seq 1 60); do docker exec "$PG_NAME" psql -h 127.0.0.1 -U postgres -tAc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
    docker exec "$PG_NAME" psql -h 127.0.0.1 -U postgres -tAc 'select 1' >/dev/null || { echo "PostgreSQL 이 기동되지 않았다" >&2; exit 1; }
    for module in guard auth bff; do
      docker exec "$PG_NAME" psql -U postgres -qc "CREATE SCHEMA suite_$module;"
    done
    docker run "${COMMON[@]}" --network "$PG_NET" -e GRADLE_USER_HOME=/cache/gradle       -e H2_TASKS="$H2_TASKS" -e PG_TASKS="$PG_TASKS"       -e TEST_PG_URL="jdbc:postgresql://$PG_NAME:5432/postgres" -e TEST_PG_USER=postgres -e TEST_PG_PASSWORD="$PG_PASSWORD"       -v "$ROOT:/src:ro" "$JAVA_IMAGE" sh -ec '
      cp -R /src /tmp/work
      cd /tmp/work
      rm -rf .git build auth/build guard/build sdk/build bff/build web/node_modules
      # H2 단계에는 TEST_PG_URL 을 넘기지 않는다(그 값이 있으면 PostgreSQL 에서 비활성인 시험이 건너뛰어진다).
      if [ -n "$H2_TASKS" ]; then env -u TEST_PG_URL ./gradlew $H2_TASKS --no-daemon --console=plain; fi
      ./gradlew $PG_TASKS --no-daemon --console=plain
    '
    ;;
  web)
    docker run "${COMMON[@]}" -e npm_config_cache=/cache/npm -v "$ROOT/web:/src:ro" "$NODE_IMAGE" sh -ec '
      mkdir -p /tmp/web
      cd /src
      tar --exclude=./node_modules --exclude=./dist -cf - . | tar -C /tmp/web -xf -
      cd /tmp/web
      npm ci --no-audit --no-fund
      npm test
      npx tsc -b
    '
    ;;
  mobile)
    docker run "${COMMON[@]}" -e CI=1 -e npm_config_cache=/cache/npm --ipc=host -v "$ROOT/web:/src:ro" "$PLAYWRIGHT_IMAGE" sh -ec '
      mkdir -p /tmp/web
      cd /src
      tar --exclude=./node_modules --exclude=./dist --exclude=./test-results -cf - . | tar -C /tmp/web -xf -
      cd /tmp/web
      npm ci --no-audit --no-fund
      npx playwright test
    '
    ;;
  *)
    echo "사용법: scripts/ci-test.sh backend|h2|pg|backend-full|web|mobile" >&2
    exit 2
    ;;
esac
