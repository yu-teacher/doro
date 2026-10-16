#!/usr/bin/env bash
# 영구 탈퇴 동기화 로컬 통합 시험: 진짜 IAM·Guard·Postgres·S3 목 + 진짜 도로 파티·도로 게임 백엔드로
# "IAM 에서 영구 탈퇴 → 각 서비스가 자기 데이터를 정리" 를 끝까지 확인한다. 브라우저는 쓰지 않는다. 로컬 전용(운영·원격 접속 없음).
#
#   e2e/run-deleted-sync.sh          # 전체 실행 후 모두 내린다(처음엔 이미지 빌드·bootRun 으로 몇 분)
#   KEEP=1 e2e/run-deleted-sync.sh   # 시험 후에도 스택·프로세스를 남긴다. 내릴 때: E2E_DS_WORK=<임시 폴더> e2e/run-deleted-sync.sh --down
#
# 필요: Docker, Java 25, Node 24, 형제 디렉터리 ../../doro-party, ../../doro-games(PARTY_DIR / GAMES_DIR 로 변경).
# 두 서비스는 로컬 origin/main(git fetch 로 받아 둔 것)을 임시 git worktree 로 받아 쓴다(원본 작업 디렉터리는 건드리지 않는다).
# 서비스 빌드는 includeBuild('../Doro') 로 이 저장소의 SDK 를 쓰므로, 임시 폴더 안에 이 저장소를 가리키는 Doro 심볼릭 링크를 둔다.
# 이미 준비된 폴더를 쓰려면 E2E_DS_WORK 아래에 party/, games/ 를 직접 두면 된다(있으면 그대로 쓰고 지우지 않는다).
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
E2E_DIR="$(pwd)"; REPO_ROOT="$(cd .. && pwd)"
set -a; source e2e.env; set +a
# 이 시험 전용 값: 액세스 토큰은 길게, 탈퇴 유예는 1일, IAM 익명화 주기는 3초
export E2E_ACCESS_TOKEN_TTL_SECONDS=900
export E2E_ACCOUNT_DELETION_GRACE_DAYS=1 E2E_ACCOUNT_PURGE_INTERVAL_MS=3000 E2E_ACCOUNT_PURGE_INITIAL_DELAY_MS=5000
export E2E_PARTY_PORT="${E2E_PARTY_PORT:-18086}" E2E_GAMES_PORT="${E2E_GAMES_PORT:-18087}"
# 스택만 쓴다(포털·BFF·게이트웨이 불필요)
COMPOSE=(docker compose -p doro-e2e --env-file e2e.env -f compose.e2e.yml)
PARTY_DIR="${PARTY_DIR:-../../doro-party}"; GAMES_DIR="${GAMES_DIR:-../../doro-games}"
WORK="${E2E_DS_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/doro-e2e-ds.XXXXXX")}"
STATE=.state
CREATED=()   # 이 실행이 만든 worktree: "<원본 저장소>|<임시 경로>"
LINK_CREATED=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
stop_port() { local pids; pids="$(lsof -ti ":$1" 2>/dev/null || true)"; [ -z "$pids" ] || kill $pids 2>/dev/null || true; }
down() {
  stop_port "$E2E_PARTY_PORT"; stop_port "$E2E_GAMES_PORT"
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  # gradle 데몬이 임시 폴더를 잡고 있을 수 있어 먼저 멈춘다
  for d in "$WORK/party" "$WORK/games"; do [ ! -x "$d/gradlew" ] || (cd "$d" && ./gradlew --stop >/dev/null 2>&1) || true; done
  local entry
  for entry in ${CREATED[@]+"${CREATED[@]}"}; do
    git -C "${entry%%|*}" worktree remove --force "${entry#*|}" >/dev/null 2>&1 || true
    git -C "${entry%%|*}" worktree prune >/dev/null 2>&1 || true
  done
  if [ "$LINK_CREATED" = 1 ]; then rm -f "$WORK/Doro"; fi
  if [ ${#CREATED[@]} -gt 0 ] && [ -z "${E2E_DS_WORK:-}" ]; then rmdir "$WORK" 2>/dev/null || true; fi
}
if [ "${1:-}" = "--down" ]; then down; log "내렸다(임시 worktree 는 E2E_DS_WORK 를 같이 줘야 정리된다)"; exit 0; fi

mkdir -p "$STATE"; chmod 700 "$STATE"
for p in "$E2E_AUTH_PORT" "$E2E_PG_PORT" "$E2E_GUARD_HTTP_PORT" "$E2E_GUARD_GRPC_PORT" "$E2E_S3_PORT" "$E2E_PARTY_PORT" "$E2E_GAMES_PORT"; do
  ! lsof -ti ":$p" >/dev/null 2>&1 || die "포트 $p 가 이미 쓰이고 있다(e2e/e2e.env 에서 바꾸거나 E2E_PARTY_PORT/E2E_GAMES_PORT 를 준다)"
done
trap '[ "${KEEP:-0}" = 1 ] || { log "정리"; down; }' EXIT

wait_http() { # url, seconds, label, [bootRun 로그: 빌드·기동이 실패하면 바로 중단]
  local deadline=$((SECONDS + $2))
  until curl -fsS -o /dev/null "$1" 2>/dev/null; do
    [ $SECONDS -lt $deadline ] || die "$3 가 $2 초 안에 준비되지 않았다"
    [ -z "${4:-}" ] || ! grep -q "BUILD FAILED" "$4" 2>/dev/null || die "$3 기동 실패($4 참고)"
    sleep 3
  done
}

log "== 1/5 서비스 소스 준비 (임시 worktree: $WORK) =="
[ -e "$WORK/Doro" ] || { ln -s "$REPO_ROOT" "$WORK/Doro"; LINK_CREATED=1; }
for pair in "party|$PARTY_DIR" "games|$GAMES_DIR"; do
  name="${pair%%|*}"; src="${pair#*|}"
  if [ -d "$WORK/$name" ]; then log "$WORK/$name 가 이미 있어 그대로 쓴다"; continue; fi
  [ -d "$src" ] || die "$src 가 없다(PARTY_DIR / GAMES_DIR 로 경로 지정)"
  git -C "$src" rev-parse --verify -q origin/main >/dev/null || die "$src 에 origin/main 이 없다(git fetch 후 다시)"
  git -C "$src" worktree add --detach "$WORK/$name" origin/main >/dev/null
  CREATED+=("$(cd "$src" && pwd)|$WORK/$name")
  log "$name: origin/main $(git -C "$WORK/$name" rev-parse --short HEAD)"
done

log "== 2/5 격리 스택 기동 (IAM · Guard · Postgres · Redis · S3) =="
"${COMPOSE[@]}" up -d --build postgres redis guard-api auth-api s3
wait_http "http://localhost:${E2E_AUTH_PORT}/actuator/health" 300 "IAM(auth)"
wait_http "http://localhost:${E2E_GUARD_HTTP_PORT}/actuator/health" 120 "Guard"

start_service() { # name dir port 환경변수...
  local name="$1" dir="$2" port="$3"; shift 3
  (cd "$dir" && env \
    SERVER_PORT="$port" \
    DB_HOST=localhost DB_PORT="$E2E_PG_PORT" DB_USER=doro_admin DB_PASSWORD="$E2E_DB_PASSWORD" \
    IAM_HOST=localhost IAM_PORT="$E2E_AUTH_PORT" \
    GUARD_GRPC_HOST=localhost GUARD_GRPC_PORT="$E2E_GUARD_GRPC_PORT" GUARD_HTTP_HOST=localhost GUARD_HTTP_PORT="$E2E_GUARD_HTTP_PORT" \
    MINIO_ENDPOINT="http://localhost:${E2E_S3_PORT}" MINIO_ACCESS_KEY=e2e MINIO_SECRET_KEY=e2e \
    "$@" \
    nohup ./gradlew bootRun --console=plain > "$E2E_DIR/$STATE/$name.log" 2>&1 &)
}
log "== 3/5 도로 파티·도로 게임 백엔드 (gateway 모드, 탈퇴 동기화 3초 주기) =="
start_service party "$WORK/party" "$E2E_PARTY_PORT" \
  DB_NAME=service_party MINIO_BUCKET=doro-party-media \
  PARTY_AUTH_MODE=gateway PARTY_OAUTH_CLIENT_ID=doro-party PARTY_COOKIE_SECURE=false PARTY_SESSION_KEY="$E2E_BFF_SESSION_KEY" \
  DORO_IAM_INTERNAL_TOKEN="$E2E_PARTY_INTERNAL_TOKEN" DORO_IAM_REVOCATION_CACHE_SECONDS=1 \
  PARTY_DELETED_SYNC_ENABLED=true PARTY_DELETED_SYNC_INTERVAL=PT3S \
  SPRING_APPLICATION_JSON='{"party":{"deleted-sync":{"initial-delay":"PT3S"}}}'
start_service games "$WORK/games" "$E2E_GAMES_PORT" \
  DB_NAME=service_games \
  GAMES_AUTH_MODE=gateway GAMES_OAUTH_CLIENT_ID=doro-games GAMES_COOKIE_SECURE=false GAMES_SESSION_KEY="$E2E_BFF_SESSION_KEY" \
  DORO_IAM_INTERNAL_TOKEN="$E2E_GAMES_INTERNAL_TOKEN" DORO_IAM_REVOCATION_CACHE_SECONDS=1 \
  GAMES_DELETED_SYNC_ENABLED=true GAMES_DELETED_SYNC_INTERVAL=PT3S \
  SPRING_APPLICATION_JSON='{"games":{"deleted-sync":{"initial-delay":"PT3S"}}}'
wait_http "http://localhost:${E2E_PARTY_PORT}/actuator/health" 600 "도로 파티" "$STATE/party.log"
wait_http "http://localhost:${E2E_GAMES_PORT}/actuator/health" 600 "도로 게임" "$STATE/games.log"

log "== 4/5 시나리오 시험 =="
set +e
node deleted-sync.mjs
rc=$?
set -e
log "== 5/5 결과 =="
[ $rc -eq 0 ] && log "통과" || log "실패(exit $rc). 로그: e2e/$STATE/{party,games}.log"
exit $rc
