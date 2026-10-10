#!/usr/bin/env bash
# 2FA(TOTP) 시크릿을 DB 에 암호화해 저장하도록 켠다. 운영 서버(mini)에서 실행한다. 키 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --check' < scripts/enable-totp-encryption.sh   # 읽기 전용 점검(키 설정 여부, 평문으로 남은 행 수)
#   ssh mini 'bash -s -- --apply' < scripts/enable-totp-encryption.sh   # 키 생성·적용, 기존 평문 시크릿을 암호문으로 이전
#
# 선행 조건: 암호화를 지원하는 auth(코드 "TOTP secret encryption")가 이미 배포돼 있어야 한다(없으면 중단한다).
# 하는 일
#   1) DB 백업(~/ops/backup.sh backup) 과 ~/doro/.env 백업
#   2) 키 생성(openssl rand -base64 32) 후 .env 의 DORO_IAM_TOTP_ENCRYPTION_KEY 에 기록 (이미 있으면 덮어쓰지 않고 중단 — 잃어버리면 2FA 를 못 쓴다)
#   3) auth-api 재생성 → 기동 시 평문 시크릿을 암호문으로 이전
#   4) 검증: healthy, DB 에 평문이 남은 행이 0, 암호문 행 수, 기동 경고 없음
# 기동이 실패하면(이전이 시작되기 전이므로) .env 를 되돌리고 auth 를 다시 만든다. 이전이 끝난 뒤의 실패는 자동으로 되돌리지 않는다:
#   키를 지우면 암호문을 못 읽어 2FA 가 모두 막히기 때문이다. 이 경우 키를 그대로 두고 원인을 확인한다.
# 끝난 뒤 사용자가 할 일: 키를 비밀번호 관리자에 보관한다.
#   ssh mini 'grep ^DORO_IAM_TOTP_ENCRYPTION_KEY= ~/doro/.env'   (복사해서 보관. 이 키를 잃으면 암호화된 2FA 는 관리자의 2FA 초기화로만 복구된다)
set -Eeuo pipefail

MODE_ARG="${1:---check}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
ENV_FILE="$DORO_DIR/.env"
AUTH=doro-auth-api
PG=doro-postgres
KEY_VAR=DORO_IAM_TOTP_ENCRYPTION_KEY

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
healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 60); do healthy "$1" && return 0; sleep 3; done; return 1; }
recreate_auth() { ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps auth-api ) >/dev/null; }
# 실행 중인 auth 의 jar 에 암호화 코드가 있으면 1, 없으면 0
has_encryption_code() {
  local n
  n="$(docker exec "$AUTH" sh -c 'unzip -l /app/app.jar 2>/dev/null | grep -c TotpSecretCipher' 2>/dev/null || true)"
  printf '%s' "${n:-0}" | head -1
}
psql_auth() { docker exec "$PG" psql -U "$(get_var "$ENV_FILE" POSTGRES_USER)" -d "$(get_var "$ENV_FILE" AUTH_DB)" -tAc "$1"; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"

PLAINTEXT_SQL="select count(*) from credentials where (totp_secret is not null and totp_secret not like 'enc:v1:%') or (pending_totp_secret is not null and pending_totp_secret not like 'enc:v1:%')"
ENCRYPTED_SQL="select count(*) from credentials where totp_secret like 'enc:v1:%' or pending_totp_secret like 'enc:v1:%'"

describe() {
  log "키(DORO_IAM_TOTP_ENCRYPTION_KEY): $([ -n "$(get_var "$ENV_FILE" $KEY_VAR)" ] && echo '설정됨' || echo '없음 (시크릿이 평문으로 저장된다)')"
  log "컨테이너 $AUTH: $(docker inspect "$AUTH" --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음)"
  log "2FA 시크릿 행: 평문 $(psql_auth "$PLAINTEXT_SQL") / 암호문 $(psql_auth "$ENCRYPTED_SQL")"
  log "auth 에 암호화 코드 포함: $(has_encryption_code)"
}

case "$MODE_ARG" in
  --check) describe; exit 0 ;;
  --apply) ;;
  *) echo "사용법: bash -s -- --check|--apply" >&2; exit 2 ;;
esac

[ -z "$(get_var "$ENV_FILE" $KEY_VAR)" ] || die "이미 키가 설정돼 있다. 덮어쓰면 기존 암호문을 읽지 못한다. 교체하려면 이전 키를 DORO_IAM_TOTP_PREVIOUS_KEYS 에 옮기고 새 키를 설정한다(문서 참고)"
[ "$(has_encryption_code)" -ge 1 ] \
  || die "실행 중인 auth 에 암호화 코드가 없다. 코드를 먼저 배포한다(푸시 후 CI 완료)"
( cd "$RUNNER_DIR" && grep -q "DORO_IAM_TOTP_ENCRYPTION_KEY" docker-compose.yml ) || die "러너 작업 디렉터리의 docker-compose.yml 이 키를 auth 에 전달하지 않는다. 배포를 먼저 한다"

STAMP="$(date +%Y%m%d-%H%M%S)"
ENV_BAK="$ENV_FILE.bak-totpenc-$STAMP"
log "1/4 백업"
"$HOME/ops/backup.sh" backup >/dev/null 2>&1 || die "DB 백업에 실패했다. 백업 없이는 진행하지 않는다"
cp -p "$ENV_FILE" "$ENV_BAK"; log "  .env 백업: $ENV_BAK"

STARTED=true
rollback() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$STARTED" = true ]; then
    trap - EXIT
    log "!! 이전이 시작되기 전에 실패했다 (exit $code). .env 를 되돌리고 auth 를 다시 만든다"
    cp -p "$ENV_BAK" "$ENV_FILE" || true
    recreate_auth || true
    exit "$code"
  fi
}
trap rollback EXIT

KEY="$(openssl rand -base64 32)"
[ "$(printf '%s' "$KEY" | base64 -d 2>/dev/null | wc -c | tr -d ' ')" = 32 ] || die "키 생성 실패"
set_var "$ENV_FILE" $KEY_VAR "$KEY"
unset KEY

log "2/4 auth-api 재생성 (기동 시 평문 시크릿을 암호문으로 이전)"
recreate_auth
wait_healthy "$AUTH" || die "auth 가 healthy 로 돌아오지 않았다"
# 여기부터는 이전이 끝났을 수 있으므로 자동으로 되돌리지 않는다
STARTED=false

log "3/4 검증"
plain="$(psql_auth "$PLAINTEXT_SQL")"; enc="$(psql_auth "$ENCRYPTED_SQL")"
log "  평문 행 $plain / 암호문 행 $enc"
[ "$plain" = 0 ] || { log "ERROR: 평문으로 남은 행이 있다. 키를 지우지 말고 auth 로그를 확인한다: docker logs $AUTH | grep -i totp"; exit 1; }
if docker logs "$AUTH" --since 5m 2>&1 | grep -q "stored in PLAINTEXT"; then
  die "auth 가 키를 읽지 못했다는 경고가 있다. 키를 지우지 말고 환경변수 전달을 확인한다"
fi
[ "$(curl -s -m 8 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health)" = 200 ] || die "auth 헬스 실패"

log "4/4 완료. 2FA 시크릿이 암호문으로 저장된다(암호문 행 $enc)."
log "지금 키를 비밀번호 관리자에 보관한다: ssh mini 'grep ^$KEY_VAR= ~/doro/.env'"
log "이 키를 지우거나 잃으면 암호화된 2FA 를 쓸 수 없다(복구: 관리자의 2FA 초기화). 롤백이 필요하면 키를 그대로 두고 문의한다."
