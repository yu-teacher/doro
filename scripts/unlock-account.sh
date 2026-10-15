#!/usr/bin/env bash
# 비상용: 로그인 잠금이 걸린 계정을 서버에서 직접 푼다. 운영 서버(mini)에서 실행한다. 비밀번호·토큰은 다루지 않는다.
# 관리자가 모두 잠겨 API(DELETE /api/v1/admin/users/{id}/lock)로 풀어 줄 사람이 없을 때 쓴다.
#
#   ssh mini 'bash -s -- --check  user@example.com' < scripts/unlock-account.sh   # 읽기 전용: 현재 잠금 상태
#   ssh mini 'bash -s -- --apply  user@example.com' < scripts/unlock-account.sh   # 계정 전체 잠금(DB) 해제
#   ssh mini 'bash -s -- --apply  user@example.com --restart-auth' < scripts/unlock-account.sh   # + IP 별 잠금(메모리)까지 초기화
#
# 잠금은 두 종류다
#   1) 계정 전체 잠금(DB: credentials.locked_until): 여러 IP 의 실패가 1시간에 30번을 넘을 때. 이 스크립트가 푼다.
#   2) (계정, IP) 쌍 잠금(auth 프로세스 메모리): 한 IP 에서 15분에 5번 틀릴 때. 그 IP 에서만 막히고 15분이면 저절로 풀린다.
#      다른 IP(예: 휴대폰 데이터)에서는 영향이 없다. 지금 당장 그 IP 에서도 풀어야 하면 --restart-auth 로 auth 를 다시 띄운다
#      (약 20초 동안 로그인·API 가 끊기고, 모든 계정의 실패 카운터가 초기화된다).
# 정지(SUSPENDED)는 잠금과 별개라 이 스크립트가 풀지 않는다(관리자 API 의 정지 해제를 쓴다).
set -Eeuo pipefail

MODE_ARG="${1:---check}"
EMAIL="${2:-}"
EXTRA="${3:-}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
ENV_FILE="$DORO_DIR/.env"
AUTH="${AUTH_CONTAINER:-doro-auth-api}"
PG="${PG_CONTAINER:-doro-postgres}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
get_var() { grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- || true; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"
[[ "$EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "이메일 형식이 이상하다: '$EMAIL' (두 번째 인자로 준다)"
case "$MODE_ARG" in --check|--apply) ;; *) echo "사용법: bash -s -- --check|--apply 이메일 [--restart-auth]" >&2; exit 2 ;; esac
case "$EXTRA" in ""|--restart-auth) ;; *) die "알 수 없는 옵션: $EXTRA" ;; esac

PGUSER_NAME="$(get_var "$ENV_FILE" POSTGRES_USER)"; AUTH_DB="$(get_var "$ENV_FILE" AUTH_DB)"
[ -n "$PGUSER_NAME" ] && [ -n "$AUTH_DB" ] || die ".env 에서 POSTGRES_USER / AUTH_DB 를 읽지 못했다"
psql_auth() { docker exec -i "$PG" psql -U "$PGUSER_NAME" -d "$AUTH_DB" -v ON_ERROR_STOP=1 -v email="$EMAIL" -tA "$@"; }

STATE_SQL="select u.status, u.role, coalesce(c.failed_attempts,0), coalesce(to_char(c.locked_until at time zone 'Asia/Seoul','YYYY-MM-DD HH24:MI:SS'),'-'), (c.locked_until is not null and c.locked_until > now())
           from users u left join credentials c on c.user_id = u.id where lower(u.email) = lower(:'email') and u.status <> 'DELETED'"

describe() {
  local row
  row="$(psql_auth <<SQL
$STATE_SQL;
SQL
)"
  [ -n "$row" ] || die "그 이메일의 계정이 없다(탈퇴 계정 제외)"
  IFS='|' read -r st role fails until locked <<<"$row"
  log "상태=$st 역할=$role 실패카운터=$fails 잠금만료(KST)=$until 지금 잠김=$([ "$locked" = t ] && echo 예 || echo 아니오)"
  [ "$st" = SUSPENDED ] && log "정지(SUSPENDED) 상태다. 이 스크립트는 정지를 풀지 않는다."
  return 0
}

if [ "$MODE_ARG" = "--check" ]; then describe; exit 0; fi

describe
n="$(psql_auth <<'SQL'
with t as (select id from users where lower(email) = lower(:'email') and status <> 'DELETED'),
     u as (update credentials set locked_until = null, failed_attempts = 0 where user_id in (select id from t) returning 1)
select count(*) from u;
SQL
)"
[ "$n" = 1 ] || die "해제하지 못했다(갱신 행 수=$n)"
logger -t doro-unlock "account lock cleared by script for $EMAIL" 2>/dev/null || true
log "계정 전체 잠금(DB)을 풀었다. 이제 새 IP 에서 로그인할 수 있다."
if [ "$EXTRA" = "--restart-auth" ]; then
  log "auth 를 다시 띄운다(IP 별 잠금 초기화). 약 20초 로그인·API 가 끊긴다."
  ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --force-recreate --no-deps auth-api ) >/dev/null
  for i in $(seq 1 60); do [ "$(docker inspect "$AUTH" --format '{{.State.Health.Status}}' 2>/dev/null)" = healthy ] && break; sleep 3; done
  [ "$(docker inspect "$AUTH" --format '{{.State.Health.Status}}' 2>/dev/null)" = healthy ] && log "auth 가 다시 healthy 다." || die "auth 가 healthy 로 돌아오지 않았다. docker logs $AUTH 를 확인한다"
else
  log "그 IP 에서 막혀 있다면 15분 기다리거나 --restart-auth 로 초기화한다(다른 IP 에서는 이미 로그인된다)."
fi
describe
