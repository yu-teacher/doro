#!/usr/bin/env bash
# 운영 서버(mini)에서 Guard 서비스 토큰 검사 모드(OFF | WARN | ENFORCE)를 바꾼다.
#
#   ssh mini 'bash -s -- ENFORCE' < scripts/set-guard-mode.sh     # 켜기
#   ssh mini 'bash -s -- WARN'    < scripts/set-guard-mode.sh     # 즉시 되돌리기 (롤백)
#
# 동작: .env 백업 -> DORO_GUARD_SECURITY_MODE 변경 -> guard-api 만 재생성 -> 검증.
# 검증에 실패하면 이전 모드로 자동 복구한다. auth/blog 는 재시작하지 않는다.
set -Eeuo pipefail

MODE="${1:-}"
case "$MODE" in OFF|WARN|ENFORCE) ;; *) echo "사용법: bash -s -- OFF|WARN|ENFORCE" >&2; exit 2 ;; esac

DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
ENV_FILE="$DORO_DIR/.env"
GUARD_CONTAINER=doro-guard-api

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

[ -f "$ENV_FILE" ] || { log "ERROR: $ENV_FILE 이 없다"; exit 1; }
OLD_MODE="$(grep '^DORO_GUARD_SECURITY_MODE=' "$ENV_FILE" | cut -d= -f2- || true)"
OLD_MODE="${OLD_MODE:-OFF}"
TOKEN="$(grep '^DORO_GUARD_SERVICE_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)"
# 토큰을 호출자별로 나눈 뒤(split-guard-tokens.sh --finalize)에는 공유 토큰이 없고 auth 토큰으로 검증한다.
[ -n "$TOKEN" ] || TOKEN="$(grep '^DORO_GUARD_AUTH_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)"
if [ "$MODE" != "OFF" ] && [ -z "$TOKEN" ]; then
  log "ERROR: 토큰(DORO_GUARD_SERVICE_TOKEN 또는 DORO_GUARD_AUTH_TOKEN)이 비어 있어 $MODE 로 바꿀 수 없다"; exit 1
fi
if [ "$MODE" = "$OLD_MODE" ]; then
  log "이미 $MODE 모드이다. 변경하지 않는다."; exit 0
fi

healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { for _ in $(seq 1 40); do healthy "$1" && return 0; sleep 3; done; return 1; }
healthy "$GUARD_CONTAINER" || { log "ERROR: $GUARD_CONTAINER 가 healthy 가 아니다"; exit 1; }

BACKUP="$ENV_FILE.bak-guardmode-$(date +%Y%m%d-%H%M%S)"
cp -p "$ENV_FILE" "$BACKUP"

set_mode() {
  local target="$1"
  if grep -q '^DORO_GUARD_SECURITY_MODE=' "$ENV_FILE"; then
    sed -i "s|^DORO_GUARD_SECURITY_MODE=.*|DORO_GUARD_SECURITY_MODE=$target|" "$ENV_FILE"
  else
    printf 'DORO_GUARD_SECURITY_MODE=%s\n' "$target" >> "$ENV_FILE"
  fi
  chmod 600 "$ENV_FILE"
  ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps guard-api ) >/dev/null
}

CHANGED=false
restore_on_failure() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$CHANGED" = true ]; then
    trap - EXIT
    log "!! 실패 (exit $code). $OLD_MODE 모드로 복구한다"
    cp -p "$BACKUP" "$ENV_FILE" || true
    ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps guard-api ) >/dev/null || true
    exit "$code"
  fi
}
trap restore_on_failure EXIT

log "Guard 모드 변경: $OLD_MODE -> $MODE (백업: $BACKUP)"
CHANGED=true
set_mode "$MODE"
wait_healthy "$GUARD_CONTAINER" || { log "ERROR: guard 가 healthy 로 돌아오지 않았다"; exit 1; }

# 검증: 컨테이너가 실제로 새 모드를 쓰고, REST 응답이 모드에 맞는지 확인한다.
APPLIED="$(docker exec "$GUARD_CONTAINER" printenv DORO_GUARD_SECURITY_MODE)"
[ "$APPLIED" = "$MODE" ] || { log "ERROR: 컨테이너 모드가 $APPLIED 이다"; exit 1; }

CHECK_BODY='{"namespace":"system","objectId":"doro","relation":"admin","subjectNamespace":"user","subjectId":"00000000-0000-0000-0000-000000000000"}'
call() { curl -s -m 8 -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8081/api/v1/guard/check -H 'Content-Type: application/json' "$@" -d "$CHECK_BODY"; }
NO_TOKEN="$(call)"
WITH_TOKEN="$(call -H "X-Doro-Service-Token: $TOKEN")"
log "  토큰 없이 호출: $NO_TOKEN / 올바른 토큰으로 호출: $WITH_TOKEN"
[ "$WITH_TOKEN" = "200" ] || { log "ERROR: 올바른 토큰인데 200 이 아니다"; exit 1; }
if [ "$MODE" = "ENFORCE" ]; then
  [ "$NO_TOKEN" = "401" ] || { log "ERROR: ENFORCE 인데 토큰 없는 호출이 401 이 아니다"; exit 1; }
else
  [ "$NO_TOKEN" = "200" ] || { log "ERROR: $MODE 인데 토큰 없는 호출이 200 이 아니다"; exit 1; }
fi
for pair in "auth:8080" "blog:8082"; do
  code="$(curl -s -m 8 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${pair#*:}/actuator/health")"
  [ "$code" = "200" ] || { log "ERROR: ${pair%%:*} 헬스 $code"; exit 1; }
done

CHANGED=false
log "완료: Guard 모드 = $MODE"
[ "$MODE" = "ENFORCE" ] && log "문제가 생기면 즉시: ssh mini 'bash -s -- WARN' < scripts/set-guard-mode.sh"
exit 0
