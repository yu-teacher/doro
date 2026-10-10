#!/usr/bin/env bash
# 첫 관리자(SUPER_ADMIN) 부트스트랩을 켜고 끈다. 운영 서버(mini)에서 실행한다. 토큰 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --check'   < scripts/bootstrap-first-admin.sh   # 읽기 전용: 켜짐 여부, 최고 관리자 수
#   ssh mini 'bash -s -- --enable'  < scripts/bootstrap-first-admin.sh   # 일회용 토큰 생성·적용(auth 재생성)
#   ssh mini 'bash -s -- --disable' < scripts/bootstrap-first-admin.sh   # 토큰 제거(auth 재생성)
#
# 쓰는 순서
#   1) --enable
#   2) 포털에서 관리자가 될 계정으로 가입·로그인하고 그 계정의 액세스 토큰으로 호출한다:
#        POST /api/v1/auth/bootstrap   Authorization: Bearer <액세스 토큰>   본문 {"token":"<.env 의 DORO_IAM_BOOTSTRAP_TOKEN>"}
#      (토큰은 ssh mini 'grep ^DORO_IAM_BOOTSTRAP_TOKEN= ~/doro/.env' 로 확인한다. 채팅에 붙여넣지 않는다.)
#   3) 성공하면 모든 세션이 끝나니 다시 로그인한다(토큰의 역할이 SUPER_ADMIN 으로 바뀐다)
#   4) --disable (최고 관리자가 생긴 뒤에는 토큰이 남아 있어도 서버가 거부하지만, 남겨 두지 않는다)
# 최고 관리자가 이미 있으면 --enable 은 아무것도 하지 않고 중단한다.
set -Eeuo pipefail

MODE_ARG="${1:---check}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
ENV_FILE="$DORO_DIR/.env"
AUTH=doro-auth-api
PG=doro-postgres
VAR=DORO_IAM_BOOTSTRAP_TOKEN

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
get_var() { grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- || true; }
set_var() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "$file.XXXXXX")"
  grep -v "^$key=" "$file" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  chmod 600 "$tmp"; mv "$tmp" "$file"
}
unset_var() {
  local file="$1" key="$2" tmp
  tmp="$(mktemp "$file.XXXXXX")"
  grep -v "^$key=" "$file" > "$tmp" || true
  chmod 600 "$tmp"; mv "$tmp" "$file"
}
healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 60); do healthy "$1" && return 0; sleep 3; done; return 1; }
recreate_auth() { ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps auth-api ) >/dev/null; }
psql_auth() { docker exec "$PG" psql -U "$(get_var "$ENV_FILE" POSTGRES_USER)" -d "$(get_var "$ENV_FILE" AUTH_DB)" -tAc "$1"; }
super_admins() { psql_auth "select count(*) from users where role = 'SUPER_ADMIN' and status <> 'DELETED'"; }
has_bootstrap_code() {
  local n
  n="$(docker exec "$AUTH" sh -c 'unzip -l /app/app.jar 2>/dev/null | grep -c BootstrapService' 2>/dev/null || true)"
  printf '%s' "${n:-0}" | head -1
}

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"

describe() {
  local t; t="$(get_var "$ENV_FILE" $VAR)"
  log "토큰($VAR): $([ -n "$t" ] && echo "설정됨(${#t}자) — 켜져 있다" || echo '없음 — 꺼져 있다')"
  log "최고 관리자 수: $(super_admins)"
  log "컨테이너 $AUTH: $(docker inspect "$AUTH" --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음)"
  log "auth 에 부트스트랩 코드 포함: $(has_bootstrap_code)"
}

case "$MODE_ARG" in
  --check) describe; exit 0 ;;
  --enable)
    [ -z "$(get_var "$ENV_FILE" $VAR)" ] || die "이미 토큰이 설정돼 있다(--disable 로 끈 뒤 다시 켠다)"
    [ "$(super_admins)" = 0 ] || die "최고 관리자가 이미 있다. 부트스트랩이 필요 없다"
    [ "$(has_bootstrap_code)" -ge 1 ] || die "실행 중인 auth 에 부트스트랩 코드가 없다. 코드를 먼저 배포한다"
    ( cd "$RUNNER_DIR" && grep -q "$VAR" docker-compose.yml ) || die "러너 작업 디렉터리의 docker-compose.yml 이 토큰을 auth 에 전달하지 않는다. 배포를 먼저 한다"
    BAK="$ENV_FILE.bak-bootstrap-$(date +%Y%m%d-%H%M%S)"
    cp -p "$ENV_FILE" "$BAK"; log ".env 백업: $BAK"
    TOKEN="$(openssl rand -hex 32)"
    set_var "$ENV_FILE" $VAR "$TOKEN"; unset TOKEN
    recreate_auth
    if ! wait_healthy "$AUTH"; then
      log "auth 가 healthy 로 돌아오지 않았다 — .env 를 되돌린다"
      cp -p "$BAK" "$ENV_FILE"; recreate_auth || true
      die "되돌렸다. docker logs $AUTH 를 확인한다"
    fi
    log "켜졌다. 이제 관리자가 될 계정으로 로그인해 POST /api/v1/auth/bootstrap 을 호출한다(스크립트 머리말 참고)."
    log "토큰 확인: ssh mini 'grep ^$VAR= ~/doro/.env'   ← 값은 채팅에 붙여넣지 않는다"
    log "끝나면 반드시: ssh mini 'bash -s -- --disable' < scripts/bootstrap-first-admin.sh"
    ;;
  --disable)
    [ -n "$(get_var "$ENV_FILE" $VAR)" ] || { log "이미 꺼져 있다."; exit 0; }
    BAK="$ENV_FILE.bak-bootstrap-$(date +%Y%m%d-%H%M%S)"
    cp -p "$ENV_FILE" "$BAK"
    unset_var "$ENV_FILE" $VAR
    recreate_auth
    wait_healthy "$AUTH" || die "auth 가 healthy 로 돌아오지 않았다(.env 백업: $BAK)"
    log "꺼졌다. 최고 관리자 수: $(super_admins)"
    ;;
  *) echo "사용법: bash -s -- --check|--enable|--disable" >&2; exit 2 ;;
esac
