#!/usr/bin/env bash
# 소셜 로그인(GitHub, Google) 공급자 키를 서버 ~/doro/.env 에 넣고 auth-api 에 반영한다. **내 컴퓨터에서** 실행한다.
# 클라이언트 ID/시크릿은 이 스크립트가 숨김 입력으로 받아 ssh 표준입력으로만 서버에 보낸다(채팅·셸 기록·프로세스 목록·화면에 남지 않는다).
#
#   bash scripts/enable-social-login.sh --check                # 읽기 전용 점검(어떤 키가 설정돼 있는지, 값은 출력하지 않는다)
#   bash scripts/enable-social-login.sh --set github           # GitHub 키 입력 → .env 에 기록(백업 후). 컨테이너는 건드리지 않는다
#   bash scripts/enable-social-login.sh --set google           # Google 키 입력 → .env 에 기록
#   bash scripts/enable-social-login.sh --recreate             # 배포가 끝난 뒤: auth-api 를 .env 로 다시 만들고 공급자 목록으로 확인
#
# 환경변수: SOCIAL_SSH_HOST(기본 mini), DORO_DIR(서버의 .env 위치 폴더, 기본 ~/doro), RUNNER_DIR(서버의 러너 체크아웃),
#           SOCIAL_PUBLIC_BASE_URL(--set 에서 공개 주소를 묻지 않으려면), SOCIAL_SSH_CMD(시험용: ssh 대신 실행할 명령)
# --recreate 는 서버의 compose 가 DORO_SOCIAL_* 를 auth 에 전달할 때만 진행한다(= 이 기능이 배포된 뒤). 기동이 실패하면 .env 를 되돌린다.
set -Eeuo pipefail

MODE="${1:---check}"
PROVIDER="${2:-}"
SSH_HOST="${SOCIAL_SSH_HOST:-mini}"

die() { echo "ERROR: $*" >&2; exit 1; }

# 서버에서 실행되는 부분. 시크릿은 표준입력에서 읽는다(명령줄에 싣지 않는다).
read -r -d '' REMOTE <<'REMOTE_EOF' || true
set -Eeuo pipefail
MODE="$1"; PROVIDER="${2:-}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
ENV_FILE="$DORO_DIR/.env"
AUTH=doro-auth-api
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
state() { [ -n "$(get_var "$ENV_FILE" "$1")" ] && echo 설정됨 || echo 없음; }
healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 60); do healthy "$1" && return 0; sleep 3; done; return 1; }
recreate_auth() { ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps auth-api ) >/dev/null; }
providers() { curl -fsS --max-time 5 "http://127.0.0.1:${AUTH_PORT:-8080}/api/v1/auth/social/providers" 2>/dev/null || echo '(응답 없음)'; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"

case "$MODE" in
  --check)
    log "DORO_SOCIAL_PUBLIC_BASE_URL: $(state DORO_SOCIAL_PUBLIC_BASE_URL)"
    for p in GITHUB GOOGLE; do
      log "$p: client-id $(state DORO_SOCIAL_${p}_CLIENT_ID) / client-secret $(state DORO_SOCIAL_${p}_CLIENT_SECRET)"
    done
    log "서버 compose 가 auth 에 전달: $( ( cd "$RUNNER_DIR" && grep -q DORO_SOCIAL_PUBLIC_BASE_URL docker-compose.yml ) 2>/dev/null && echo 예 || echo '아니오(소셜 로그인 코드가 아직 배포되지 않았다)')"
    log "컨테이너 $AUTH: $(docker inspect "$AUTH" --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음)"
    log "공급자 목록: $(providers)"
    ;;
  --set)
    case "$PROVIDER" in github|google) ;; *) die "공급자는 github 또는 google" ;; esac
    UP="$(printf '%s' "$PROVIDER" | tr a-z A-Z)"
    IFS= read -r BASE; IFS= read -r CID; IFS= read -r SECRET
    [ -n "$CID" ] && [ -n "$SECRET" ] || die "클라이언트 ID/시크릿이 비어 있다"
    case "$BASE$CID$SECRET" in *[[:space:]\"\'\\\$\`]*) die "값에 공백·따옴표·백슬래시·\$·백틱이 있다(.env 를 깨뜨린다). 복사한 값을 확인한다" ;; esac
    case "$BASE" in https://*) ;; *) die "공개 주소는 https:// 로 시작해야 한다" ;; esac
    BASE="${BASE%/}"
    BAK="$ENV_FILE.bak-social-$(date +%Y%m%d-%H%M%S)"
    cp -p "$ENV_FILE" "$BAK"; log ".env 백업: $BAK"
    set_var "$ENV_FILE" DORO_SOCIAL_PUBLIC_BASE_URL "$BASE"
    set_var "$ENV_FILE" "DORO_SOCIAL_${UP}_CLIENT_ID" "$CID"
    set_var "$ENV_FILE" "DORO_SOCIAL_${UP}_CLIENT_SECRET" "$SECRET"
    log "기록했다: DORO_SOCIAL_PUBLIC_BASE_URL / DORO_SOCIAL_${UP}_CLIENT_ID / DORO_SOCIAL_${UP}_CLIENT_SECRET (값은 출력하지 않는다)"
    log "아직 반영 전이다. 배포가 끝나면: bash scripts/enable-social-login.sh --recreate"
    ;;
  --recreate)
    ( cd "$RUNNER_DIR" && grep -q DORO_SOCIAL_PUBLIC_BASE_URL docker-compose.yml ) \
      || die "서버 compose 가 DORO_SOCIAL_* 를 auth 에 전달하지 않는다. 소셜 로그인 코드가 배포된 뒤에 다시 실행한다"
    [ -n "$(get_var "$ENV_FILE" DORO_SOCIAL_PUBLIC_BASE_URL)" ] || die "DORO_SOCIAL_PUBLIC_BASE_URL 이 없다. --set 을 먼저 한다"
    BAK="$ENV_FILE.bak-social-recreate-$(date +%Y%m%d-%H%M%S)"
    cp -p "$ENV_FILE" "$BAK"; log ".env 백업: $BAK"
    log "auth-api 재생성"
    recreate_auth
    if wait_healthy "$AUTH"; then
      log "healthy. 공급자 목록: $(providers)"
    else
      log "auth-api 가 healthy 가 되지 않았다. 이전 .env 로 되돌린다"
      cp -p "$BAK" "$ENV_FILE"; recreate_auth
      wait_healthy "$AUTH" && die "되돌렸다(.env 복원, healthy). 원인: docker logs $AUTH" || die "되돌렸지만 healthy 가 아니다. 직접 확인한다: docker logs $AUTH"
    fi
    ;;
  *) echo "사용법: --check | --set github|google | --recreate" >&2; exit 2 ;;
esac
REMOTE_EOF

# 서버에 명령을 보낸다. 표준입력은 시크릿 전달용이다.
remote() {
  if [ -n "${SOCIAL_SSH_CMD:-}" ]; then
    $SOCIAL_SSH_CMD bash -c "$REMOTE" _ "$@"
  else
    ssh "$SSH_HOST" "bash -c $(printf '%q' "$REMOTE") _ $(printf '%q ' "$@")"
  fi
}

case "$MODE" in
  --check|--recreate)
    remote "$MODE" </dev/null ;;
  --set)
    case "$PROVIDER" in github|google) ;; *) die "사용법: --set github|google" ;; esac
    BASE="${SOCIAL_PUBLIC_BASE_URL:-}"
    if [ -z "$BASE" ]; then
      read -r -p "공개 주소(https://로 시작, 공급자 앱에 등록한 주소와 같게): " BASE
    fi
    echo "공급자 앱의 콜백 주소: ${BASE%/}/api/v1/auth/social/$PROVIDER/callback"
    read -r -p "$PROVIDER Client ID: " CID
    read -r -s -p "$PROVIDER Client secret (입력은 보이지 않는다): " SECRET; echo
    printf '%s\n%s\n%s\n' "$BASE" "$CID" "$SECRET" | remote --set "$PROVIDER"
    unset SECRET ;;
  *) echo "사용법: bash scripts/enable-social-login.sh --check | --set github|google | --recreate" >&2; exit 2 ;;
esac
