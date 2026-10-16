#!/usr/bin/env bash
# scripts/deploy-rollback.sh 를 임시 compose 프로젝트로 시험한다(운영 컨테이너는 건드리지 않는다). Docker 와 busybox 이미지가 필요하다.
#   scripts/test-deploy-rollback.sh
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="rbtest$$"
export COMPOSE_FILE="$WORK/compose.yml"
export ROLLBACK_STATE="$WORK/state"
export ROLLBACK_PREFIX="rbtest-rollback$$"
export ROLLBACK_HEALTH_TIMEOUT=30
FAILURES=0
log() { printf '%s\n' "$*"; }
expect() { # 설명 명령...
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then log "  ok  $desc"; else log "  실패  $desc"; FAILURES=$((FAILURES + 1)); fi
}
cleanup() {
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  docker rmi -f "$ROLLBACK_PREFIX/a:prev" "$ROLLBACK_PREFIX/b:prev" >/dev/null 2>&1 || true
  docker rmi -f "${COMPOSE_PROJECT_NAME}-a" "${COMPOSE_PROJECT_NAME}-b" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

cat > "$WORK/Dockerfile" <<'DF'
FROM busybox:1.36
ARG V=0
RUN echo "$V" > /v
CMD ["sh", "-c", "sleep 3600"]
DF
cat > "$COMPOSE_FILE" <<YML
services:
  a:
    build: { context: $WORK, args: { V: "\${VA:-1}" } }
    image: ${COMPOSE_PROJECT_NAME}-a
    healthcheck: { test: ["CMD", "test", "-f", "/v"], interval: 2s, timeout: 2s, retries: 10 }
  b:
    image: ${COMPOSE_PROJECT_NAME}-b
  c:
    image: busybox:1.36
    command: ["sleep", "3600"]
YML

version_of() { docker compose exec -T "$1" cat /v | tr -d '\r\n'; }
cid_of() { docker compose ps -q "$1" | head -1; }

log "== 1) v1 배포 → 스냅샷 =="
# b 는 빌드하지 않는 고정 이미지(배포로 이미지가 바뀌지 않는 서비스), a 는 배포마다 새로 빌드되는 서비스, c 는 외부 이미지 서비스
docker build -q -t "${COMPOSE_PROJECT_NAME}-b" --build-arg V=1 "$WORK" >/dev/null
VA=1 docker compose up -d --build >/dev/null 2>&1
B_BEFORE="$(cid_of b)"; C_BEFORE="$(cid_of c)"
"$HERE/deploy-rollback.sh" snapshot >/dev/null
expect "스냅샷 파일에 실행 중인 서비스 3개가 적힌다" test "$(wc -l < "$ROLLBACK_STATE" | tr -d ' ')" = 3
expect "이전 이미지에 보존 태그가 붙는다" docker image inspect "$ROLLBACK_PREFIX/a:prev"

log "== 2) 서비스 a 만 v2 로 배포(잘못된 배포를 흉내) =="
VA=2 docker compose up -d --build >/dev/null 2>&1
expect "a 는 v2 로 바뀌었다" test "$(version_of a)" = 2

log "== 3) 복원: a 만 v1 로 돌아가고, b(이미지 안 바뀜)·c(이미지 서비스)는 그대로 =="
"$HERE/deploy-rollback.sh" restore 2>&1 | sed 's/^/    | /'
expect "a 가 v1 로 복원됐다" test "$(version_of a)" = 1
expect "b 컨테이너는 다시 만들어지지 않았다" test "$(cid_of b)" = "$B_BEFORE"
expect "c 컨테이너는 다시 만들어지지 않았다" test "$(cid_of c)" = "$C_BEFORE"

log "== 4) 되돌릴 것이 없으면 아무 일도 하지 않는다(다시 복원) =="
A_NOW="$(cid_of a)"
"$HERE/deploy-rollback.sh" restore >/dev/null
expect "a 는 그대로다" test "$(cid_of a)" = "$A_NOW"

log "== 5) 스냅샷이 없으면 복원은 조용히 끝난다 =="
rm -f "$ROLLBACK_STATE"
expect "스냅샷 없이도 종료 코드 0" "$HERE/deploy-rollback.sh" restore

log "== 6) 새 이미지 prune 에도 보존 태그가 옛 이미지를 지켜 준다 =="
"$HERE/deploy-rollback.sh" snapshot >/dev/null
VA=3 docker compose up -d --build >/dev/null 2>&1
docker image prune -f >/dev/null 2>&1
"$HERE/deploy-rollback.sh" restore >/dev/null
expect "prune 뒤에도 v1 로 복원된다" test "$(version_of a)" = 1

log "== 7) 서비스 하나의 스냅샷이 실패해도 나머지는 계속하고 스크립트는 성공으로 끝난다(안전장치가 배포를 막으면 안 된다) =="
# docker 를 감싸는 가짜 실행 파일: b 의 보존 태그를 붙일 때만 실패시킨다(실제 서버에서 이미지를 못 찾아 스냅샷이 실패했던 상황을 흉내)
REAL_DOCKER="$(command -v docker)"
mkdir -p "$WORK/shim"
cat > "$WORK/shim/docker" <<SH
#!/usr/bin/env bash
if [ "\$1" = "tag" ] && [[ "\$3" == */b:prev ]]; then echo "simulated tag failure" >&2; exit 1; fi
exec "$REAL_DOCKER" "\$@"
SH
chmod +x "$WORK/shim/docker"
PATH="$WORK/shim:$PATH" "$HERE/deploy-rollback.sh" snapshot >/dev/null 2>&1; RC=$?
expect "스냅샷은 실패 코드로 끝나지 않는다" test "$RC" = 0
expect "실패한 b 는 상태 파일에서 빠지고" test "$(grep -c '^b	' "$ROLLBACK_STATE")" = 0
expect "나머지(a, c)는 기록된다" test "$(wc -l < "$ROLLBACK_STATE" | tr -d ' ')" = 2

log "== 8) 이름이 실행 중인 이미지와 다른 서비스(빌드만 되고 배포가 실패한 상태)는 스냅샷 대상에서 뺀다 =="
"$HERE/deploy-rollback.sh" snapshot >/dev/null 2>&1        # 기준: 지금 a 는 v3 로 실행 중이고 이름도 v3
VA=9 docker compose build a >/dev/null 2>&1              # 이름만 새 이미지(v9)로 옮겨 간다. 컨테이너는 그대로.
"$HERE/deploy-rollback.sh" snapshot >/dev/null 2>&1
expect "이름이 앞서간 a 는 상태 파일에서 빠진다" test "$(grep -c '^a	' "$ROLLBACK_STATE")" = 0
expect "나머지(b, c)는 기록된다" test "$(wc -l < "$ROLLBACK_STATE" | tr -d ' ')" = 2

if [ "$FAILURES" -eq 0 ]; then log "모두 통과"; else log "$FAILURES 건 실패"; exit 1; fi
