#!/usr/bin/env bash
# Guard 서비스 토큰을 "모두가 같이 쓰는 공유 토큰 하나"에서 "호출자별 토큰(auth, blog)"으로 나눈다.
# 운영 서버(mini)에서 실행한다. 토큰 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --check'    < scripts/split-guard-tokens.sh   # 읽기 전용 점검
#   ssh mini 'bash -s -- --apply'    < scripts/split-guard-tokens.sh   # 1단계: 호출자별 토큰 발급/적용 (공유 토큰은 유지)
#   ssh mini 'bash -s -- --finalize' < scripts/split-guard-tokens.sh   # 2단계: 공유 토큰 제거 (적용 후 10분 이상 지난 뒤)
#   ssh mini 'bash -s -- --rollback' < scripts/split-guard-tokens.sh   # 가장 최근 백업(.env 두 개)으로 되돌리고 guard/auth/blog 재생성
#
# 선행 조건: 호출자별 토큰을 받을 수 있는 Guard(코드 커밋 "per-caller service tokens")가 이미 배포돼 있어야 한다.
#            (배포돼 있지 않으면 --apply 의 검증에서 실패하고 자동으로 원래대로 돌아간다.)
#
# 단계 1 (--apply)
#   1) ~/doro/.env, ~/doro-blog/.env 백업
#   2) auth/blog 용 토큰을 각각 새로 만든다 (openssl rand, 64자)
#   3) ~/doro/.env 에 DORO_GUARD_SERVICE_TOKENS=auth:..,blog:.. 와 DORO_GUARD_AUTH_TOKEN 을 쓰고,
#      ~/doro-blog/.env 의 DORO_GUARD_SERVICE_TOKEN 을 blog 토큰으로 바꾼다. 기존 공유 토큰은 Guard 에 남겨 둔다.
#   4) guard-api 를 먼저 다시 만들어 새 토큰 둘과 기존 공유 토큰이 모두 통과하는지 확인하고,
#      그 다음 auth-api, blog-backend 를 다시 만들어 각 컨테이너가 자기 토큰을 갖고 있는지 확인한다.
#   어느 단계든 실패하면 두 .env 를 되돌리고 세 컨테이너를 다시 만든다.
# 단계 2 (--finalize)
#   Guard 로그에서 공유 토큰 사용 경고가 없는지 확인한 뒤, Guard 의 공유 토큰을 비운다.
#   이후 예전 공유 토큰은 거부된다. 실패하면 되돌린다.
set -Eeuo pipefail

MODE_ARG="${1:---check}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
BLOG_DIR="${BLOG_DIR:-$HOME/doro-blog}"
BLOG_COMPOSE="docker-compose.prod.yml"
ENV_FILE="$DORO_DIR/.env"
BLOG_ENV="$BLOG_DIR/.env"
MARKER="$DORO_DIR/.guard-tokens-applied-at"
GUARD=doro-guard-api; AUTH=doro-auth-api; BLOG=doro-blog-backend
GRACE_MIN="${GRACE_MIN:-10}"
SHARED_WARNING="Guard call with the deprecated shared service token"   # guard ServiceAuthProperties.SHARED_TOKEN_WARNING 과 같아야 한다
GUARD_CHECK_URL="http://127.0.0.1:8081/api/v1/guard/check"
CHECK_BODY='{"namespace":"system","objectId":"doro","relation":"admin","subjectNamespace":"user","subjectId":"00000000-0000-0000-0000-000000000000"}'

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

get_var() { grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- || true; }
# 파일의 KEY 줄을 바꾸거나 추가한다. 값은 로그에 남기지 않는다. 권한은 600 으로 유지한다.
set_var() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "$file.XXXXXX")"
  grep -v "^$key=" "$file" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  chmod 600 "$tmp"; mv "$tmp" "$file"
}
drop_var() { local file="$1" key="$2" tmp; tmp="$(mktemp "$file.XXXXXX")"; grep -v "^$key=" "$file" > "$tmp" || true; chmod 600 "$tmp"; mv "$tmp" "$file"; }

healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 50); do healthy "$1" && return 0; sleep 3; done; return 1; }
code_with() { curl -s -m 8 -o /dev/null -w '%{http_code}' -X POST "$GUARD_CHECK_URL" -H 'Content-Type: application/json' ${1:+-H "X-Doro-Service-Token: $1"} -d "$CHECK_BODY"; }

recreate_guard_auth() { ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps "$@" ) >/dev/null; }
recreate_blog() { ( cd "$BLOG_DIR" && docker compose -f "$BLOG_COMPOSE" up -d --no-build --no-deps blog-backend ) >/dev/null; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"
[ -f "$BLOG_ENV" ] || die "$BLOG_ENV 가 없다"
GUARD_MODE="$(get_var "$ENV_FILE" DORO_GUARD_SECURITY_MODE)"; GUARD_MODE="${GUARD_MODE:-OFF}"
SHARED="$(get_var "$ENV_FILE" DORO_GUARD_SERVICE_TOKEN)"
HAVE_SPLIT=false; [ -n "$(get_var "$ENV_FILE" DORO_GUARD_SERVICE_TOKENS)" ] && HAVE_SPLIT=true

describe() {
  log "Guard 모드: $GUARD_MODE"
  log "공유 토큰(DORO_GUARD_SERVICE_TOKEN): $([ -n "$SHARED" ] && echo '설정됨' || echo '없음')"
  log "호출자별 토큰(DORO_GUARD_SERVICE_TOKENS): $($HAVE_SPLIT && echo "설정됨 ($(get_var "$ENV_FILE" DORO_GUARD_SERVICE_TOKENS | tr ',' '\n' | cut -d: -f1 | tr '\n' ' '))" || echo '없음')"
  log "auth 토큰(DORO_GUARD_AUTH_TOKEN): $([ -n "$(get_var "$ENV_FILE" DORO_GUARD_AUTH_TOKEN)" ] && echo '설정됨' || echo '없음 (공유 토큰 사용)')"
  log "blog 토큰: $([ "$(get_var "$BLOG_ENV" DORO_GUARD_SERVICE_TOKEN)" = "$SHARED" ] && echo '공유 토큰과 같음' || echo '별도 값')"
  for c in $GUARD $AUTH $BLOG; do log "컨테이너 $c: $(docker inspect "$c" --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음)"; done
  log "최근 15분 Guard 로그의 공유 토큰 사용 경고: $(docker logs --since 15m "$GUARD" 2>&1 | grep -c "$SHARED_WARNING" || true)건"
}

case "$MODE_ARG" in
  --check) describe; exit 0 ;;
  --apply|--finalize) ;;
  --rollback)
    latest() { ls -1t "$1".bak-splittokens-* 2>/dev/null | head -1; }
    ENV_LAST="$(latest "$ENV_FILE")"; BLOG_LAST="$(latest "$BLOG_ENV")"
    [ -n "$ENV_LAST" ] && [ -n "$BLOG_LAST" ] || die "되돌릴 백업(.env.bak-splittokens-*)이 없다"
    log "복원: $ENV_LAST , $BLOG_LAST"
    cp -p "$ENV_LAST" "$ENV_FILE"; cp -p "$BLOG_LAST" "$BLOG_ENV"; rm -f "$MARKER"
    recreate_guard_auth guard-api auth-api; recreate_blog
    for c in $GUARD $AUTH $BLOG; do wait_healthy "$c" || die "$c 가 healthy 로 돌아오지 않았다"; done
    log "완료. 이전 상태로 되돌렸다."; exit 0 ;;
  *) echo "사용법: bash -s -- --check|--apply|--finalize|--rollback" >&2; exit 2 ;;
esac

for c in $GUARD $AUTH $BLOG; do healthy "$c" || die "$c 가 healthy 가 아니다. 먼저 정상으로 만든 뒤 실행한다"; done
STAMP="$(date +%Y%m%d-%H%M%S)"
ENV_BAK="$ENV_FILE.bak-splittokens-$STAMP"; BLOG_BAK="$BLOG_ENV.bak-splittokens-$STAMP"
cp -p "$ENV_FILE" "$ENV_BAK"; cp -p "$BLOG_ENV" "$BLOG_BAK"
log "백업: $ENV_BAK , $BLOG_BAK"

STARTED=false
rollback() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$STARTED" = true ]; then
    trap - EXIT
    log "!! 실패 (exit $code). 두 .env 를 되돌리고 guard/auth/blog 를 다시 만든다"
    cp -p "$ENV_BAK" "$ENV_FILE" || true
    cp -p "$BLOG_BAK" "$BLOG_ENV" || true
    recreate_guard_auth guard-api auth-api || true
    recreate_blog || true
    exit "$code"
  fi
}
trap rollback EXIT

expect_no_token() { [ "$GUARD_MODE" = ENFORCE ] && echo 401 || echo 200; }

if [ "$MODE_ARG" = "--apply" ]; then
  $HAVE_SPLIT && die "이미 호출자별 토큰이 설정돼 있다. 바꾸려면 먼저 백업을 확인하고 값을 직접 지운 뒤 다시 실행한다"
  [ "$GUARD_MODE" != OFF ] || log "참고: Guard 모드가 OFF 라 토큰 검사 자체가 꺼져 있다. 토큰은 나뉘지만 ENFORCE 가 되기 전에는 효과가 없다"
  AUTH_TOKEN="$(openssl rand -hex 32)"; BLOG_TOKEN="$(openssl rand -hex 32)"
  [ "${#AUTH_TOKEN}" -eq 64 ] && [ "${#BLOG_TOKEN}" -eq 64 ] && [ "$AUTH_TOKEN" != "$BLOG_TOKEN" ] || die "토큰 생성 실패"

  STARTED=true
  set_var "$ENV_FILE" DORO_GUARD_AUTH_TOKEN "$AUTH_TOKEN"
  set_var "$ENV_FILE" DORO_GUARD_SERVICE_TOKENS "auth:$AUTH_TOKEN,blog:$BLOG_TOKEN"
  set_var "$BLOG_ENV" DORO_GUARD_SERVICE_TOKEN "$BLOG_TOKEN"

  log "1/3 guard-api 재생성 (공유 토큰 유지 + 호출자별 토큰 추가)"
  recreate_guard_auth guard-api
  wait_healthy "$GUARD" || die "guard 가 healthy 로 돌아오지 않았다 (호출자별 토큰을 지원하지 않는 버전이거나 설정 오류)"
  r_auth="$(code_with "$AUTH_TOKEN")"; r_blog="$(code_with "$BLOG_TOKEN")"; r_old="$(code_with "$SHARED")"; r_none="$(code_with "")"; r_bad="$(code_with "wrong-token")"
  log "  auth 토큰 $r_auth / blog 토큰 $r_blog / 기존 공유 토큰 $r_old / 토큰 없음 $r_none / 틀린 토큰 $r_bad"
  [ "$r_auth" = 200 ] && [ "$r_blog" = 200 ] || die "새 토큰이 Guard 에서 통과하지 않는다"
  if [ -n "$SHARED" ]; then [ "$r_old" = 200 ] || die "전환 기간인데 기존 공유 토큰이 거부된다"; fi
  [ "$r_none" = "$(expect_no_token)" ] && [ "$r_bad" = "$(expect_no_token)" ] || die "토큰 없음/틀린 토큰의 응답이 모드($GUARD_MODE)와 맞지 않는다"

  log "2/3 auth-api, blog-backend 재생성 (각자 자기 토큰 사용)"
  recreate_guard_auth auth-api
  recreate_blog
  wait_healthy "$AUTH" || die "auth 가 healthy 로 돌아오지 않았다"
  wait_healthy "$BLOG" || die "blog 가 healthy 로 돌아오지 않았다"

  log "3/3 컨테이너가 실제로 받은 토큰 확인"
  [ "$(docker exec "$AUTH" printenv DORO_GUARD_SERVICE_TOKEN)" = "$AUTH_TOKEN" ] || die "auth 컨테이너 토큰이 auth 토큰이 아니다"
  [ "$(docker exec "$BLOG" printenv DORO_GUARD_SERVICE_TOKEN)" = "$BLOG_TOKEN" ] || die "blog 컨테이너 토큰이 blog 토큰이 아니다"
  [ "$(code_with "$(docker exec "$AUTH" printenv DORO_GUARD_SERVICE_TOKEN)")" = 200 ] || die "auth 컨테이너의 토큰이 Guard 에서 통과하지 않는다"
  [ "$(code_with "$(docker exec "$BLOG" printenv DORO_GUARD_SERVICE_TOKEN)")" = 200 ] || die "blog 컨테이너의 토큰이 Guard 에서 통과하지 않는다"
  for pair in "auth:8080" "blog:8082"; do
    [ "$(curl -s -m 8 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${pair#*:}/actuator/health")" = 200 ] || die "${pair%%:*} 헬스 실패"
  done

  STARTED=false
  date +%s > "$MARKER"; chmod 600 "$MARKER"
  log "완료. 이제 호출자별 토큰이 쓰이고, 기존 공유 토큰은 아직 Guard 에서 통과한다."
  log "로그인/글쓰기 등 평소 흐름을 써 본 뒤 ${GRACE_MIN}분 이상 지나면: ssh mini 'bash -s -- --finalize' < scripts/split-guard-tokens.sh"
  log "문제가 생기면 즉시: ssh mini 'bash -s -- --rollback' < scripts/split-guard-tokens.sh"
  exit 0
fi

# ------------------------------------------------------------ --finalize
$HAVE_SPLIT || die "호출자별 토큰이 아직 없다. 먼저 --apply 를 실행한다"
[ -n "$SHARED" ] || die "공유 토큰이 이미 없다. 할 일이 없다"
[ -f "$MARKER" ] || die "$MARKER 가 없다. --apply 를 먼저 실행한다"
elapsed=$(( ( $(date +%s) - $(cat "$MARKER") ) / 60 ))
[ "$elapsed" -ge "$GRACE_MIN" ] || die "--apply 후 ${elapsed}분밖에 지나지 않았다. ${GRACE_MIN}분 이상 지난 뒤 실행한다 (GRACE_MIN 으로 조정)"
shared_uses="$(docker logs --since "${elapsed}m" "$GUARD" 2>&1 | grep -c "$SHARED_WARNING" || true)"
[ "$shared_uses" -eq 0 ] || { docker logs --since "${elapsed}m" "$GUARD" 2>&1 | grep "$SHARED_WARNING" | tail -3; die "아직 공유 토큰으로 Guard 를 호출하는 곳이 있다($shared_uses건). 호출자를 찾아 토큰을 바꾼 뒤 다시 실행한다"; }
AUTH_TOKEN="$(get_var "$ENV_FILE" DORO_GUARD_AUTH_TOKEN)"; BLOG_TOKEN="$(get_var "$BLOG_ENV" DORO_GUARD_SERVICE_TOKEN)"
[ "$(docker exec "$AUTH" printenv DORO_GUARD_SERVICE_TOKEN)" = "$AUTH_TOKEN" ] || die "auth 컨테이너가 자기 토큰을 쓰고 있지 않다"
[ "$(docker exec "$BLOG" printenv DORO_GUARD_SERVICE_TOKEN)" = "$BLOG_TOKEN" ] || die "blog 컨테이너가 자기 토큰을 쓰고 있지 않다"
[ "$BLOG_TOKEN" != "$SHARED" ] && [ "$AUTH_TOKEN" != "$SHARED" ] || die "호출자 토큰이 공유 토큰과 같다"

STARTED=true
set_var "$ENV_FILE" DORO_GUARD_SERVICE_TOKEN ""
log "guard-api 재생성 (공유 토큰 제거)"
recreate_guard_auth guard-api
wait_healthy "$GUARD" || die "guard 가 healthy 로 돌아오지 않았다"
r_auth="$(code_with "$AUTH_TOKEN")"; r_blog="$(code_with "$BLOG_TOKEN")"; r_old="$(code_with "$SHARED")"
log "  auth 토큰 $r_auth / blog 토큰 $r_blog / 예전 공유 토큰 $r_old"
[ "$r_auth" = 200 ] && [ "$r_blog" = 200 ] || die "호출자별 토큰이 거부된다"
[ "$r_old" = "$(expect_no_token)" ] || die "예전 공유 토큰의 응답($r_old)이 모드($GUARD_MODE) 기대값과 다르다"
for pair in "auth:8080" "blog:8082"; do
  [ "$(curl -s -m 8 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${pair#*:}/actuator/health")" = 200 ] || die "${pair%%:*} 헬스 실패"
done
STARTED=false
rm -f "$MARKER"
log "완료. 예전 공유 토큰은 더 이상 쓰이지 않는다. (백업에만 남아 있으니 확인 후 지워도 된다: $ENV_BAK)"
