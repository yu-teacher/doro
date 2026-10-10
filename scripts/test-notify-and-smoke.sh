#!/usr/bin/env bash
# notify-telegram.sh 와 post-deploy-smoke.sh 를 가짜 curl/docker 로 시험한다(네트워크·서버 없이).
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
FAILURES=0
pass() { echo "  ok  $1"; }
fail() { echo "  FAIL $1"; FAILURES=$((FAILURES + 1)); }
expect() { # <설명> <조건 명령...>
  local desc="$1"; shift
  if "$@"; then pass "$desc"; else fail "$desc"; fi
}

SECRET_TOKEN="123456:SECRET-TOKEN-VALUE"

# ---- 가짜 curl: 호출 인자와 표준입력을 기록한다. FAKE_CURL_EXIT 로 실패를 흉내 낸다.
cat > "$WORK/fake-curl-notify" <<'SH'
#!/usr/bin/env bash
{ echo "ARGS: $*"; cat; } >> "$FAKE_CURL_LOG"
exit "${FAKE_CURL_EXIT:-0}"
SH
chmod +x "$WORK/fake-curl-notify"

echo "== notify-telegram.sh"
# notify <환경변수...> -- <메시지...>: 깨끗한 환경에서 실행하고 표준출력·종료 코드를 $WORK/out, $WORK/code 에 남긴다.
notify() {
  local envs=()
  while [ "$1" != "--" ]; do envs+=("$1"); shift; done; shift
  set +e
  env -i PATH="$PATH" HOME="$WORK" CURL_BIN="$WORK/fake-curl-notify" FAKE_CURL_LOG="$WORK/curl.log" "${envs[@]}" "$HERE/notify-telegram.sh" "$@" > "$WORK/out" 2>&1
  echo $? > "$WORK/code"
  set -e
}
has() { grep -qF -- "$2" "$1"; }
hasnt() { ! grep -qF -- "$2" "$1"; }

: > "$WORK/curl.log"
notify DORO_ENV_FILE="$WORK/none.env" -- "메시지"
expect "설정이 없으면 건너뛰고(종료 코드 0) curl 을 부르지 않는다" bash -c "[ ! -s '$WORK/curl.log' ] && [ \$(cat '$WORK/code') = 0 ]"
expect "건너뛴다고 안내한다" has "$WORK/out" "건너뜁니다"

printf 'TELEGRAM_BOT_TOKEN=%s\nTELEGRAM_CHAT_ID=4242\nOTHER=1\n' "$SECRET_TOKEN" > "$WORK/doro.env"
: > "$WORK/curl.log"
notify DORO_ENV_FILE="$WORK/doro.env" -- "배포 완료"
expect "설정이 있으면 sendMessage 로 보낸다" has "$WORK/curl.log" "sendMessage"
grep '^ARGS:' "$WORK/curl.log" > "$WORK/args.log"
expect "토큰은 curl 인자에 없다" hasnt "$WORK/args.log" "$SECRET_TOKEN"
expect "토큰은 표준입력(-K -)으로 전달된다" has "$WORK/curl.log" "$SECRET_TOKEN"
expect "토큰이 화면 출력에 나오지 않는다" hasnt "$WORK/out" "$SECRET_TOKEN"
expect "채팅 ID 와 메시지를 보낸다" bash -c "grep -qF 'chat_id=4242' '$WORK/curl.log' && grep -qF 'text=배포 완료' '$WORK/curl.log'"

: > "$WORK/curl.log"
set +e; printf '여러 줄\n메시지' | env -i PATH="$PATH" HOME="$WORK" CURL_BIN="$WORK/fake-curl-notify" FAKE_CURL_LOG="$WORK/curl.log" DORO_ENV_FILE="$WORK/doro.env" "$HERE/notify-telegram.sh" >/dev/null 2>&1; set -e
expect "표준입력으로 받은 메시지도 보낸다" has "$WORK/curl.log" "text=여러 줄"

: > "$WORK/curl.log"
notify DORO_ENV_FILE="$WORK/doro.env" TELEGRAM_MAX_CHARS=100 -- "$(python3 -c 'print("가"*5000)')"
expect "너무 긴 메시지는 잘라서 보낸다" has "$WORK/curl.log" "잘림"
expect "잘린 메시지는 한도 안이다" bash -c "[ \$(wc -c < '$WORK/curl.log') -lt 1500 ]"

notify DORO_ENV_FILE="$WORK/doro.env" FAKE_CURL_EXIT=22 -- "x"
expect "전송이 실패해도 종료 코드는 0 이다(배포를 막지 않는다)" bash -c "[ \$(cat '$WORK/code') = 0 ]"
expect "실패하면 경고를 낸다" has "$WORK/out" "실패"
expect "실패해도 토큰이 출력에 나오지 않는다" hasnt "$WORK/out" "$SECRET_TOKEN"


echo "== format-deploy-message.sh"
fmt() { env -i PATH="$PATH" "$@" "$HERE/format-deploy-message.sh" > "$WORK/out" 2>&1; }
SMOKE_OK=$'✅ 허브 / (200)\n---\n점검 16개 중 16개 통과'
SMOKE_BAD=$'✅ 허브 / (200)\n❌ 비로그인 관리자 목록: 응답 500, 기대 401\n---\n점검 16개 중 15개 통과'

fmt TEST_RESULT=success DEPLOY_RESULT=success SMOKE="$SMOKE_OK" SHA=7a98399abcdef COMMIT_MSG=$'feat: 제목\n\n본문은 보이지 않는다' ACTOR=yu RUN_URL=https://example/run/1
expect "성공: 완료 표시, 짧은 커밋, 제목 한 줄, 점검 요약, 실행 주소" bash -c "grep -q '✅ 배포 완료' '$WORK/out' && grep -q '7a98399 feat: 제목' '$WORK/out' && grep -q '점검 16개 중 16개 통과' '$WORK/out' && grep -q 'https://example/run/1' '$WORK/out'"
expect "커밋 본문은 넣지 않는다" hasnt "$WORK/out" "본문은 보이지 않는다"

fmt TEST_RESULT=failure DEPLOY_RESULT=skipped SHA=abcdef1234 COMMIT_MSG=x
expect "테스트 실패: 배포하지 않았다고 알린다" bash -c "grep -q '테스트 실패' '$WORK/out' && grep -q '이전 상태' '$WORK/out'"

fmt TEST_RESULT=success DEPLOY_RESULT=failure SMOKE="$SMOKE_BAD" SHA=abcdef1234 COMMIT_MSG=x
expect "배포 후 점검 실패: 실패한 항목 줄을 보여 준다" bash -c "grep -q '❌ 배포 또는 배포 후 점검 실패' '$WORK/out' && grep -q '❌ 비로그인 관리자 목록: 응답 500' '$WORK/out'"
expect "실패 때는 '점검 N개 통과' 요약을 성공처럼 보이게 쓰지 않는다" hasnt "$WORK/out" "15개 중 15개"

fmt TEST_RESULT=success DEPLOY_RESULT=success SHA=abcdef1234 COMMIT_MSG='$(touch /tmp/pwned) `id` "; rm -rf /'
expect "커밋 메시지에 셸 문자가 있어도 실행되지 않고 그대로 보인다" bash -c "grep -qF '\$(touch /tmp/pwned)' '$WORK/out' && [ ! -e /tmp/pwned ]"

fmt TEST_RESULT=success DEPLOY_RESULT=skipped SHA=abcdef1234 COMMIT_MSG=x
expect "배포를 건너뛴 경우도 알린다" has "$WORK/out" "건너뛰었습니다"

fmt TEST_RESULT=skipped DEPLOY_RESULT=weird SHA=abcdef1234 COMMIT_MSG=x
expect "알 수 없는 결과는 그대로 드러낸다" has "$WORK/out" "알 수 없습니다"

echo "== post-deploy-smoke.sh"
# 가짜 curl: 경로별 기대 코드를 돌려준다. STUB_BAD 가 경로에 들어 있으면 500 을 돌려준다.
cat > "$WORK/fake-curl-smoke" <<'SH'
#!/usr/bin/env bash
method=GET; url=""
while [ $# -gt 0 ]; do case "$1" in -X) method="$2"; shift 2 ;; -o|-w|-m|-H|-d) shift 2 ;; -sk) shift ;; *) url="$1"; shift ;; esac; done
path="${url#https://127.0.0.1}"
if [ -n "${STUB_BAD:-}" ] && [[ "$path" == *"$STUB_BAD"* ]]; then printf 500; exit 0; fi
case "$method $path" in
  "GET /"|"GET /login"|"GET /blog/"|"GET /party/"|"GET /games/") printf 200 ;;
  "GET /api/v1/admin/users"|PUT*suspension|"POST /blog/api/v1/posts"|"POST /blog/api/v1/series") printf 401 ;;
  "POST /api/v1/auth/token/refresh") printf 400 ;;
  "POST /api/v1/auth/lookup") printf 404 ;;
  *) printf 000 ;;
esac
SH
cat > "$WORK/fake-docker" <<'SH'
#!/usr/bin/env bash
[ "${STUB_UNHEALTHY:-}" = "${@: -1}" ] && { echo "running unhealthy"; exit 0; }
echo "running healthy"
SH
chmod +x "$WORK/fake-curl-smoke" "$WORK/fake-docker"
smoke() { env SMOKE_RETRIES=1 SMOKE_RETRY_SLEEP=0 CURL_BIN="$WORK/fake-curl-smoke" DOCKER_BIN="$WORK/fake-docker" "$@" "$HERE/post-deploy-smoke.sh"; }

run_smoke() { set +e; smoke "$@" > "$WORK/out" 2>&1; echo $? > "$WORK/code"; set -e; }

run_smoke
expect "모두 정상이면 종료 코드 0" bash -c "[ \$(cat '$WORK/code') = 0 ]"
expect "통과 요약이 나온다" has "$WORK/out" "16개 중 16개 통과"
expect "실패 줄이 없다" hasnt "$WORK/out" "❌"

run_smoke STUB_BAD=/api/v1/admin/users
expect "응답 코드가 기대와 다르면(예: 500) 종료 코드 1" bash -c "[ \$(cat '$WORK/code') = 1 ]"
expect "어느 항목이 왜 실패했는지 보인다" has "$WORK/out" "❌ 비로그인 관리자 목록: 응답 500, 기대 401"

run_smoke STUB_UNHEALTHY=doro-auth-api
expect "컨테이너가 healthy 가 아니면 실패한다" bash -c "[ \$(cat '$WORK/code') = 1 ]"
expect "컨테이너 상태가 보인다" has "$WORK/out" "❌ 컨테이너 doro-auth-api: running unhealthy"

echo
if [ "$FAILURES" -eq 0 ]; then echo "모두 통과"; else echo "$FAILURES 건 실패"; exit 1; fi
