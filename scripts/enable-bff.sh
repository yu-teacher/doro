#!/usr/bin/env bash
# doro-bff(로그인·세션·토큰 갱신을 서비스 밖에서 맡는 BFF)를 켜기 위한 서버 준비. 운영 서버(mini)에서 실행한다. 비밀 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --check' < scripts/enable-bff.sh   # 읽기 전용: 준비 상태
#   ssh mini 'bash -s -- --apply' < scripts/enable-bff.sh   # ~/doro/.env 에 필요한 값을 채운다(이미 있으면 건드리지 않는다)
#
# --apply 가 하는 일(컨테이너는 재생성하지 않는다. 다음 배포가 .env 를 읽어 반영한다)
#   1) BFF_SESSION_KEY       : 저장하는 토큰을 암호화하는 키(openssl rand -base64 32). 한 번 정하면 바꾸지 않는다(바꾸면 모든 BFF 세션이 무효가 된다).
#   2) BFF_IAM_INTERNAL_TOKEN: BFF 가 IAM 내부 API(탈퇴 사용자 목록)를 부를 때 쓰는 서비스 토큰.
#                              같은 값을 DORO_IAM_INTERNAL_TOKENS 에 'bff:<토큰>' 으로 추가한다(기존 항목은 그대로).
#   3) 데이터베이스 doro_bff 를 만든다(배포 단계도 같은 일을 하며, 있으면 아무 일도 하지 않는다).
# 백업: ~/doro/.env.bak-bff-<시각>. 키 값은 비밀번호 관리자에 따로 보관한다(서버 .env 에서 직접 복사, 채팅에 붙여넣지 않는다).
set -Eeuo pipefail

MODE_ARG="${1:---check}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
ENV_FILE="$DORO_DIR/.env"
PG=doro-postgres

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
pg_user() { local u; u="$(get_var "$ENV_FILE" POSTGRES_USER)"; printf '%s' "${u:-doro_admin}"; }
bff_db() { local d; d="$(get_var "$ENV_FILE" BFF_DB)"; printf '%s' "${d:-doro_bff}"; }
db_exists() { [ "$(docker exec "$PG" psql -U "$(pg_user)" -d postgres -tAc "select 1 from pg_database where datname = '$(bff_db)'" 2>/dev/null)" = 1 ]; }
has_bff_token_entry() { get_var "$ENV_FILE" DORO_IAM_INTERNAL_TOKENS | tr ',' '\n' | grep -q '^bff:'; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"

describe() {
  local k t o
  k="$(get_var "$ENV_FILE" BFF_SESSION_KEY)"; t="$(get_var "$ENV_FILE" BFF_IAM_INTERNAL_TOKEN)"
  log "BFF_SESSION_KEY          : $([ -n "$k" ] && echo "설정됨(${#k}자)" || echo '없음')"
  log "BFF_IAM_INTERNAL_TOKEN   : $([ -n "$t" ] && echo "설정됨(${#t}자)" || echo '없음')"
  log "DORO_IAM_INTERNAL_TOKENS : $(has_bff_token_entry && echo "'bff:' 항목 있음" || echo "'bff:' 항목 없음")"
  o="$(get_var "$ENV_FILE" BFF_PUBLIC_ORIGIN)"; [ -n "$o" ] || o="$(get_var "$ENV_FILE" DORO_IAM_ISSUER)"
  log "공개 주소(BFF_PUBLIC_ORIGIN, 없으면 DORO_IAM_ISSUER): ${o:-없음 — 로그인 주소가 틀어진다}"
  log "데이터베이스 $(bff_db): $(db_exists && echo 있음 || echo 없음)"
  log "컨테이너 doro-bff-api: $(docker inspect doro-bff-api --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음)"
}

case "$MODE_ARG" in
  --check) describe; exit 0 ;;
  --apply)
    BAK="$ENV_FILE.bak-bff-$(date +%Y%m%d-%H%M%S)"
    cp -p "$ENV_FILE" "$BAK"; log ".env 백업: $BAK"
    if [ -z "$(get_var "$ENV_FILE" BFF_SESSION_KEY)" ]; then
      set_var "$ENV_FILE" BFF_SESSION_KEY "$(openssl rand -base64 32)"; log "BFF_SESSION_KEY 를 만들었다"
    else log "BFF_SESSION_KEY 는 이미 있다(그대로 둔다)"; fi
    if [ -z "$(get_var "$ENV_FILE" BFF_IAM_INTERNAL_TOKEN)" ]; then
      TOKEN="$(openssl rand -hex 32)"
      set_var "$ENV_FILE" BFF_IAM_INTERNAL_TOKEN "$TOKEN"
    else
      TOKEN="$(get_var "$ENV_FILE" BFF_IAM_INTERNAL_TOKEN)"; log "BFF_IAM_INTERNAL_TOKEN 은 이미 있다(그대로 둔다)"
    fi
    if has_bff_token_entry; then
      log "DORO_IAM_INTERNAL_TOKENS 에 'bff:' 항목이 이미 있다(그대로 둔다)"
    else
      CUR="$(get_var "$ENV_FILE" DORO_IAM_INTERNAL_TOKENS)"
      set_var "$ENV_FILE" DORO_IAM_INTERNAL_TOKENS "${CUR:+$CUR,}bff:$TOKEN"; log "DORO_IAM_INTERNAL_TOKENS 에 'bff:' 항목을 추가했다"
    fi
    unset TOKEN CUR
    if db_exists; then log "데이터베이스 $(bff_db) 는 이미 있다"
    else docker exec "$PG" psql -U "$(pg_user)" -d postgres -qc "CREATE DATABASE $(bff_db);" && log "데이터베이스 $(bff_db) 를 만들었다"; fi
    log "끝. 컨테이너는 건드리지 않았다. 다음 배포(main 머지)가 새 값으로 doro-bff-api 를 올리고 auth 를 다시 만든다."
    describe
    ;;
  *) echo "사용법: bash -s -- --check|--apply" >&2; exit 2 ;;
esac
