#!/usr/bin/env bash
# 배포 직후 서비스가 실제로 살아 있는지 점검한다. 운영 서버(mini)의 러너에서 배포 뒤에 실행한다.
#
#   scripts/post-deploy-smoke.sh            # 점검하고 결과를 한 줄씩 출력, 하나라도 실패하면 종료 코드 1
#
# 점검 항목
#  1) 컨테이너: 실행 중이고 헬스체크가 있으면 healthy 인지
#  2) 게이트웨이 경유 화면: 허브·로그인·블로그·파티·게임이 200 인지
#  3) 비로그인 요청의 응답 코드: 로그인이 필요한 API 가 500 이 아니라 401/400 으로 거절하는지
#     (배포 직후 "비로그인 POST 가 500" 회귀를 놓친 적이 있어서 항상 확인한다)
# 기동 직후에는 잠깐 실패할 수 있어 항목마다 몇 번 다시 시도한다.
set -uo pipefail

BASE="${SMOKE_BASE:-https://127.0.0.1}"
CURL_BIN="${CURL_BIN:-curl}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
RETRIES="${SMOKE_RETRIES:-10}"
RETRY_SLEEP="${SMOKE_RETRY_SLEEP:-3}"
CONTAINERS="${SMOKE_CONTAINERS:-doro-auth-api doro-guard-api doro-blog-backend doro-party-api doro-games-api}"
SKIP_CONTAINERS="${SMOKE_SKIP_CONTAINERS:-0}"
ZERO_UUID=00000000-0000-0000-0000-000000000000

PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); echo "✅ $1"; }
fail() { FAIL=$((FAIL + 1)); echo "❌ $1"; }

# probe <이름> <기대 코드> <메서드> <경로> [curl 추가 인자...]
probe() {
  local name="$1" expect="$2" method="$3" path="$4"; shift 4
  local code="" i
  for i in $(seq 1 "$RETRIES"); do
    code="$("$CURL_BIN" -sk -o /dev/null -w '%{http_code}' -m 8 -X "$method" "$@" "$BASE$path" 2>/dev/null || true)"
    [ "$code" = "$expect" ] && { ok "$name ($code)"; return 0; }
    sleep "$RETRY_SLEEP"
  done
  fail "$name: 응답 ${code:-없음}, 기대 $expect"
}

if [ "$SKIP_CONTAINERS" != 1 ]; then
  for c in $CONTAINERS; do
    state=""
    for i in $(seq 1 "$RETRIES"); do
      state="$("$DOCKER_BIN" inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$c" 2>/dev/null || true)"
      case "$state" in "running healthy"|"running -") break ;; esac
      sleep "$RETRY_SLEEP"
    done
    case "$state" in
      "running healthy"|"running -") ok "컨테이너 $c ($state)" ;;
      "") fail "컨테이너 $c: 없음" ;;
      *) fail "컨테이너 $c: $state" ;;
    esac
  done
fi

JSON='Content-Type: application/json'
probe "허브 /"                     200 GET  /
probe "포털 로그인 /login"          200 GET  /login
probe "블로그 /blog/"               200 GET  /blog/
probe "파티 /party/"                200 GET  /party/
probe "게임 /games/"                200 GET  /games/
probe "비로그인 관리자 목록"          401 GET  /api/v1/admin/users
probe "비로그인 계정 정지"           401 PUT  "/api/v1/admin/users/$ZERO_UUID/suspension" -H "$JSON" -d '{"reason":"smoke"}'
probe "비로그인 토큰 갱신(토큰 없음)"  400 POST /api/v1/auth/token/refresh -H "$JSON" -d '{}'
probe "없는 계정 조회"               404 POST /api/v1/auth/lookup -H "$JSON" -d '{"email":"smoke-nobody@doro.invalid"}'
# 본문 검증(400)이 인증보다 먼저 일어나지 않도록 제목·이름 두 필드를 모두 담는다(운영에서 401 을 확인한 본문)
probe "비로그인 블로그 글 작성"        401 POST /blog/api/v1/posts -H "$JSON" -d '{"title":"x","name":"x"}'
probe "비로그인 블로그 시리즈 작성"     401 POST /blog/api/v1/series -H "$JSON" -d '{"title":"x","name":"x"}'

echo "---"
echo "점검 $((PASS + FAIL))개 중 $PASS개 통과"
[ "$FAIL" -eq 0 ]
