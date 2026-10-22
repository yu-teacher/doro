#!/usr/bin/env bash
# 외부(제3자) 앱 시점의 OIDC 시험: 격리 스택(IAM · Guard · Postgres · Redis) + 포털 개발 서버를 띄우고, 표준 OIDC 클라이언트 라이브러리(openid-client)로
# 만든 가짜 외부 앱이 "Doro 로 로그인"을 끝까지(발견 → 인가 → 동의 → 토큰 → id_token 검증 → userinfo → 갱신 → 폐기) 하는지 진짜 Chromium 으로 확인한다.
# 운영처럼 한 주소를 발급자로 쓰기 위해 포털 개발 서버 주소(/oauth2, /.well-known 은 IAM 으로 프록시)를 issuer 로 한다. 개발·운영 스택과 포트·이름이 겹치지 않는다.
#
#   e2e/run-third-party.sh            # 전체 실행
#   KEEP=1 e2e/run-third-party.sh     # 시험 후에도 스택을 남긴다(내릴 때: e2e/run-third-party.sh --down)
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
set -a; source e2e.env; set +a
export E2E_ISSUER="http://localhost:${E2E_PORTAL_PORT}"
COMPOSE=(docker compose -p doro-e2e-ext --env-file e2e.env -f compose.e2e.yml)
STATE=.state

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
stop_port() { local pids; pids="$(lsof -ti ":$1" 2>/dev/null || true)"; [ -z "$pids" ] || kill $pids 2>/dev/null || true; }
down() { stop_port "$E2E_PORTAL_PORT"; "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; }
if [ "${1:-}" = "--down" ]; then down; log "내렸다"; exit 0; fi

mkdir -p "$STATE"; chmod 700 "$STATE"
for p in "$E2E_AUTH_PORT" "$E2E_PORTAL_PORT" "$E2E_PG_PORT" "${E2E_EXT_APP_PORT:-19001}"; do
  ! lsof -ti ":$p" >/dev/null 2>&1 || die "포트 $p 가 이미 쓰이고 있다(다른 e2e 스택이 떠 있나? e2e/run.sh --down 또는 e2e/run-third-party.sh --down)"
done
trap '[ "${KEEP:-0}" = 1 ] || { log "정리"; down; }' EXIT

wait_http() { local deadline=$((SECONDS + $2)); until curl -fsS -o /dev/null "$1" 2>/dev/null; do [ $SECONDS -lt $deadline ] || die "$3 가 $2 초 안에 준비되지 않았다"; sleep 3; done; }

log "== 1/4 격리 스택 기동 (IAM · Guard · Postgres · Redis) =="
"${COMPOSE[@]}" up -d --build postgres redis guard-api auth-api
wait_http "http://localhost:${E2E_AUTH_PORT}/actuator/health" 300 "IAM(auth)"
wait_http "http://localhost:${E2E_GUARD_HTTP_PORT}/actuator/health" 120 "Guard"

log "== 2/4 포털 개발 서버 =="
[ -d ../web/node_modules ] || (cd ../web && npm ci --no-audit --no-fund)
(cd ../web && VITE_DEV_API_TARGET="http://localhost:${E2E_AUTH_PORT}" nohup npx vite --port "$E2E_PORTAL_PORT" --strictPort > "../e2e/$STATE/portal-ext.log" 2>&1 &)
wait_http "http://localhost:${E2E_PORTAL_PORT}/" 90 "포털"
wait_http "http://localhost:${E2E_PORTAL_PORT}/.well-known/openid-configuration" 30 "발견 문서(포털 프록시)"

log "== 3/4 클라이언트·계정 준비 =="
[ -d node_modules ] || npm install --no-audit --no-fund >/dev/null
node setup-third-party.mjs

log "== 4/4 외부 앱 시험 =="
set +e
npx playwright test -c playwright.third-party.config.ts ${PW_ARGS:-}
rc=$?
set -e
[ $rc -eq 0 ] && log "통과" || log "실패(exit $rc). 로그: e2e/$STATE/*.log"
exit $rc
