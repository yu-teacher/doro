#!/usr/bin/env bash
# CI 에서 테스트를 Docker 컨테이너 안에서 실행한다. 러너(운영 서버)에는 Java 21 만 있고 Node 가 없어서,
# 프로젝트가 요구하는 Java 25 / Node 24 를 컨테이너로 가져온다. 로컬에서도 같은 방식으로 실행할 수 있다.
#
#   scripts/ci-test.sh backend   # auth, guard, sdk 전체 테스트 (./gradlew test, H2)
#   scripts/ci-test.sh pg        # guard, auth 전체 테스트를 실제 PostgreSQL 위에서 (Flyway + ddl-auto=validate)
#   scripts/ci-test.sh web       # 웹 의존성 설치 + vitest + 타입 검사 (npm ci / npm test / tsc -b)
#
# - 저장소는 읽기 전용으로 마운트하고 컨테이너 안의 임시 디렉터리로 복사해서 빌드한다. 호스트 작업 디렉터리에
#   build/, node_modules 가 남지 않는다(배포 단계의 docker 빌드 컨텍스트와 actions/checkout 청소를 방해하지 않음).
# - 같은 호스트에서 운영 서비스가 돌아가므로 CPU/메모리를 제한한다 (CI_CPUS, CI_MEMORY 로 조정).
# - Gradle/npm 캐시는 $CI_CACHE_DIR(기본 ~/.cache/doro-ci)에 남겨 두 번째 실행부터 빠르다.
set -Eeuo pipefail

TARGET="${1:-}"
ROOT="${CI_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
CACHE="${CI_CACHE_DIR:-$HOME/.cache/doro-ci}"
# amazoncorretto 이미지에는 gradlew 가 쓰는 xargs/tar 가 없어서 Gradle 공식 이미지(Java 25)를 쓴다. Gradle 버전은 gradle-wrapper 와 맞춘다.
JAVA_IMAGE="${CI_JAVA_IMAGE:-gradle:9.5.1-jdk25}"
NODE_IMAGE="${CI_NODE_IMAGE:-node:24-alpine}"

mkdir -p "$CACHE/gradle" "$CACHE/npm"
LIMITS=(--cpus "${CI_CPUS:-2}" --memory "${CI_MEMORY:-4g}")
# 호스트 사용자로 실행해 캐시/산출물이 root 소유가 되지 않게 한다.
COMMON=(--rm "${LIMITS[@]}" --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$CACHE:/cache")

case "$TARGET" in
  backend)
    docker run "${COMMON[@]}" -e GRADLE_USER_HOME=/cache/gradle -v "$ROOT:/src:ro" "$JAVA_IMAGE" sh -ec '
      # 읽기 전용 소스를 복사한 뒤 산출물/캐시 디렉터리를 지운다 .
      cp -R /src /tmp/work
      cd /tmp/work
      rm -rf .git build auth/build guard/build sdk/build web/node_modules
      ./gradlew test --no-daemon --console=plain
    '
    ;;
  pg)
    # 일회용 PostgreSQL 을 같은 도커 네트워크에 띄우고, 그 위에서 마이그레이션·엔티티·제약·동시성을 검증한다.
    # (H2 + create-drop 은 PostgreSQL 전용 SQL, FK, 부분 유니크 인덱스, 엔티티-스키마 불일치를 검증하지 못한다)
    PG_NAME="ci-pg-$$"; PG_NET="ci-net-$$"; PG_PASSWORD="ci-only-$$"
    cleanup_pg() { docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; docker network rm "$PG_NET" >/dev/null 2>&1 || true; }
    trap cleanup_pg EXIT
    docker network create "$PG_NET" >/dev/null
    docker run -d --name "$PG_NAME" --network "$PG_NET" -e POSTGRES_PASSWORD="$PG_PASSWORD" postgres:16-alpine >/dev/null
    for _ in $(seq 1 30); do docker exec "$PG_NAME" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
    docker exec "$PG_NAME" pg_isready -U postgres >/dev/null || { echo "PostgreSQL 이 기동되지 않았다" >&2; exit 1; }
    for module in guard auth; do
      docker exec "$PG_NAME" psql -U postgres -qc "CREATE SCHEMA suite_$module;"
    done
    docker run "${COMMON[@]}" --network "$PG_NET" -e GRADLE_USER_HOME=/cache/gradle \
      -e TEST_PG_URL="jdbc:postgresql://$PG_NAME:5432/postgres" -e TEST_PG_USER=postgres -e TEST_PG_PASSWORD="$PG_PASSWORD" \
      -v "$ROOT:/src:ro" "$JAVA_IMAGE" sh -ec '
      cp -R /src /tmp/work
      cd /tmp/work
      rm -rf .git build auth/build guard/build sdk/build web/node_modules
      ./gradlew :guard:testOnPostgres :auth:testOnPostgres --no-daemon --console=plain
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
  *)
    echo "사용법: scripts/ci-test.sh backend|pg|web" >&2
    exit 2
    ;;
esac
