#!/usr/bin/env bash
# enable-social-login.sh 시험: 서버 대신 임시 폴더를 쓴다(SOCIAL_SSH_CMD=env). 시크릿이 출력에 나오지 않는지, .env 가 안전하게 바뀌는지 본다.
set -uo pipefail
cd "$(dirname "$0")"
SCRIPT="$PWD/enable-social-login.sh"
PASS=0; FAIL=0
ok() { PASS=$((PASS+1)); echo "  ok - $1"; }
no() { FAIL=$((FAIL+1)); echo "  FAIL - $1"; }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else no "$name"; fi; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/doro" "$TMP/runner"
export DORO_DIR="$TMP/doro" RUNNER_DIR="$TMP/runner" SOCIAL_SSH_CMD=env
ENV="$TMP/doro/.env"
printf 'POSTGRES_PASSWORD=keepme\nDORO_SOCIAL_GITHUB_CLIENT_ID=old-id\n' > "$ENV"; chmod 600 "$ENV"

SECRET='s3cr3t-VALUE-0123456789abcdef'
run_set() { printf '%s\n%s\n%s\n' "$2" "$3" "$4" | bash "$SCRIPT" --set "$1" 2>&1; }

echo "--check"
OUT="$(bash "$SCRIPT" --check 2>&1)"
check "check: 값 대신 설정 여부만 보여 준다" bash -c '[[ "$1" == *"GITHUB: client-id 설정됨 / client-secret 없음"* && "$1" != *old-id* ]]' _ "$OUT"
check "check: compose 가 아직 전달하지 않음을 알린다" bash -c '[[ "$1" == *"아니오"* ]]' _ "$OUT"

echo "--set github"
OUT="$(run_set github https://doro.example/ my-client-id "$SECRET")"; RC=$?
check "set: 성공" test "$RC" -eq 0
check "set: 출력에 시크릿·ID 가 없다" bash -c '[[ "$1" != *s3cr3t* && "$1" != *my-client-id* ]]' _ "$OUT"
check "set: 키 세 개가 기록되고(끝 슬래시 제거) 기존 값은 유지" bash -c 'grep -qx "DORO_SOCIAL_PUBLIC_BASE_URL=https://doro.example" "$1" && grep -qx "DORO_SOCIAL_GITHUB_CLIENT_ID=my-client-id" "$1" && grep -qx "DORO_SOCIAL_GITHUB_CLIENT_SECRET=s3cr3t-VALUE-0123456789abcdef" "$1" && grep -qx "POSTGRES_PASSWORD=keepme" "$1"' _ "$ENV"
check "set: 옛 ID 가 중복되지 않는다" test "$(grep -c '^DORO_SOCIAL_GITHUB_CLIENT_ID=' "$ENV")" -eq 1
check "set: 권한 600 유지" test "$(stat -f %Lp "$ENV" 2>/dev/null || stat -c %a "$ENV")" = 600
check "set: 백업 파일이 생기고 옛 내용을 담는다" bash -c 'grep -q old-id "$(ls "$1"/.env.bak-social-* | head -1)"' _ "$TMP/doro"

echo "--set google (github 값 유지)"
run_set google https://doro.example gid "$SECRET" >/dev/null
check "google 추가 후에도 github 키가 남는다" bash -c 'grep -q "^DORO_SOCIAL_GITHUB_CLIENT_ID=my-client-id" "$1" && grep -q "^DORO_SOCIAL_GOOGLE_CLIENT_ID=gid" "$1"' _ "$ENV"

echo "--set 입력 검증"
BEFORE="$(cat "$ENV")"
run_set github http://insecure.example id "$SECRET" >/dev/null; check "http 공개 주소 거부" test $? -ne 0
run_set github https://doro.example 'id with space' "$SECRET" >/dev/null; check "공백 든 값 거부" test $? -ne 0
run_set github https://doro.example id 'abc"def' >/dev/null; check "따옴표 든 값 거부" test $? -ne 0
run_set github https://doro.example id '$(touch /tmp/x)' >/dev/null; check "\$ 든 값 거부" test $? -ne 0
run_set github https://doro.example '' "$SECRET" >/dev/null; check "빈 ID 거부" test $? -ne 0
run_set twitter https://doro.example id "$SECRET" >/dev/null; check "모르는 공급자 거부" test $? -ne 0
check "거부된 입력은 .env 를 바꾸지 않는다" test "$(cat "$ENV")" = "$BEFORE"

echo "--recreate"
OUT="$(bash "$SCRIPT" --recreate 2>&1)"; RC=$?
check "recreate: compose 가 키를 전달하지 않으면 거부" bash -c '[ "$1" -ne 0 ] && [[ "$2" == *"배포된 뒤"* ]]' _ "$RC" "$OUT"
check "알 수 없는 옵션은 사용법과 종료 코드 2" bash -c 'bash "$1" --nope >/dev/null 2>&1; [ $? -eq 2 ]' _ "$SCRIPT"

echo "ssh 경로(가짜 ssh 가 서버의 로그인 셸처럼 명령 문자열을 실행)"
mkdir -p "$TMP/bin"
cat > "$TMP/bin/ssh" <<'SSH'
#!/usr/bin/env bash
# ssh <host> <command string>: 서버는 명령 문자열을 셸로 해석한다. 호스트는 무시한다.
shift
exec bash -c "$*"
SSH
chmod +x "$TMP/bin/ssh"
OUT="$(printf '%s\n%s\n%s\n' https://doro.example via-ssh-id "$SECRET" | SOCIAL_SSH_CMD= PATH="$TMP/bin:$PATH" bash "$SCRIPT" --set google 2>&1)"; RC=$?
check "ssh 경로로도 --set 이 동작하고 시크릿이 출력에 없다" bash -c '[ "$1" -eq 0 ] && grep -q "^DORO_SOCIAL_GOOGLE_CLIENT_ID=via-ssh-id" "$2" && [[ "$3" != *s3cr3t* ]]' _ "$RC" "$ENV" "$OUT"

echo; echo "통과 $PASS / 실패 $FAIL"; [ "$FAIL" -eq 0 ]
