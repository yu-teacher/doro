#!/usr/bin/env bash
# auto-reboot 스크립트의 판단(필요할 때만, 바쁘면 건너뜀, 부팅 후 확인)을 시험한다. 실제로 재부팅하지 않는다(REBOOT_CMD 를 가짜로 바꾼다).
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRE="$HERE/auto-reboot/doro-auto-reboot.sh"
POST="$HERE/auto-reboot/doro-auto-reboot-after.sh"
WORK="$(mktemp -d)"; PORT="${TEST_PORT:-18766}"; SRV_PID=""; HOLD_PID=""; SLEEP_PID=""
cleanup() { for p in "$SRV_PID" "$HOLD_PID" "$SLEEP_PID"; do [ -z "$p" ] || kill "$p" 2>/dev/null || true; done; rm -rf "$WORK"; }
trap cleanup EXIT
mkdir -p "$WORK/state" "$WORK/bin"
printf '#!/usr/bin/env bash\necho "${@: -1}" >> "%s/log"\n' "$WORK" > "$WORK/logger"; chmod +x "$WORK/logger"
printf '#!/usr/bin/env bash\ntouch "%s/rebooted"\n' "$WORK" > "$WORK/fake-reboot"; chmod +x "$WORK/fake-reboot"

pass=0; fail=0
ok() { pass=$((pass + 1)); printf '  통과  %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  실패  %s\n' "$1"; [ -f "$WORK/log" ] && sed 's/^/        log: /' "$WORK/log"; }
reset() { rm -f "$WORK/log" "$WORK/rebooted" "$WORK/state/pending" "$WORK/reboot-required"; }
# 실제 러너 작업(이 시험이 CI 안에서 돌 수도 있다)에 걸리지 않게 기본 패턴은 존재하지 않는 이름으로 둔다. 3번 시험만 가짜 러너 이름을 넘긴다.
run_pre() { env AUTO_REBOOT_CONF=/nonexistent RUNNER_PROCESS_PATTERN="__no_such_runner_process__" AUTO_REBOOT_HOST=testhost LOGGER="$WORK/logger" REBOOT_CMD="$WORK/fake-reboot" REBOOT_REQUIRED_FILE="$WORK/reboot-required" STATE_DIR="$WORK/state" CHECK_DOCKER=no APT_LOCK_FILES="" "$@" bash "$PRE" >/dev/null 2>&1 || true; }

# 1) 재부팅 대기가 없으면 아무것도 하지 않는다
reset; run_pre
{ grep -q "result=not-needed" "$WORK/log" && [ ! -e "$WORK/rebooted" ]; } && ok "대기 없음: 재부팅하지 않고 not-needed 기록" || bad "대기 없음"

# 2) 대기가 있고 막는 것이 없으면 재부팅한다
reset; touch "$WORK/reboot-required"; printf 'linux-image-x\nlibc6\n' > "$WORK/reboot-required.pkgs"; run_pre
{ grep -q "result=rebooting" "$WORK/log" && [ -e "$WORK/rebooted" ] && [ -f "$WORK/state/pending" ] && grep -q "pkgs=linux-image-x,libc6" "$WORK/log"; } && ok "대기 있음: 재부팅하고 pending 표시와 패키지 기록" || bad "재부팅"

# 3) CI 작업이 진행 중이면 건너뛴다
reset; touch "$WORK/reboot-required"
printf '#!/usr/bin/env bash\nsleep 30\n' > "$WORK/bin/Runner.Worker.fake"; chmod +x "$WORK/bin/Runner.Worker.fake"
"$WORK/bin/Runner.Worker.fake" & SLEEP_PID=$!; sleep 0.3
run_pre RUNNER_PROCESS_PATTERN="bin/Runner[.]Worker[.]fake"
{ grep -q "result=skipped reason=CI작업진행중" "$WORK/log" && [ ! -e "$WORK/rebooted" ]; } && ok "CI 작업 중: 건너뜀" || bad "CI 작업 중"
kill "$SLEEP_PID" 2>/dev/null || true; SLEEP_PID=""

# 4) 백업이 잠금을 쥐고 있으면 건너뛴다(flock 이 있는 환경에서만)
if command -v flock >/dev/null 2>&1; then
  reset; touch "$WORK/reboot-required" "$WORK/backup.lock"
  ( exec 8>"$WORK/backup.lock"; flock 8; sleep 20 ) & HOLD_PID=$!; sleep 0.5
  run_pre BACKUP_LOCK_FILES="$WORK/backup.lock"
  { grep -q "reason=백업진행중" "$WORK/log" && [ ! -e "$WORK/rebooted" ]; } && ok "백업 중: 건너뜀" || bad "백업 중"
  kill "$HOLD_PID" 2>/dev/null || true; HOLD_PID=""
else
  printf '  건너뜀 백업 잠금 시험(이 환경에는 flock 이 없다)\n'
fi

# 5) 컨테이너가 이미 비정상이면 건너뛴다
reset; touch "$WORK/reboot-required"
printf '#!/usr/bin/env bash\necho "doro-blog-backend Up 3 minutes (unhealthy)"\n' > "$WORK/bin/docker"; chmod +x "$WORK/bin/docker"
run_pre CHECK_DOCKER=yes PATH="$WORK/bin:$PATH"
{ grep -q "reason=컨테이너비정상:doro-blog-backend" "$WORK/log" && [ ! -e "$WORK/rebooted" ]; } && ok "컨테이너 비정상: 건너뜀" || bad "컨테이너 비정상"
rm -f "$WORK/bin/docker"

# 6) 부팅 후 확인: 가짜 서버로 성공/실패 확인
cat > "$WORK/server.py" <<'PY'
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        self.send_response(200); self.send_header("Content-Length", "1"); self.end_headers(); self.wfile.write(b"x")
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
python3 "$WORK/server.py" "$PORT" & SRV_PID=$!; sleep 0.7
run_post() { env AUTO_REBOOT_CONF=/nonexistent AUTO_REBOOT_HOST=testhost LOGGER="$WORK/logger" STATE_DIR="$WORK/state" CHECK_DOCKER=no AFTER_INTERVAL_SEC=1 "$@" bash "$POST" >/dev/null 2>&1 || true; }
reset; printf 'EPOCH=%s\nKERNEL=old\n' "$(( $(date +%s) - 90 ))" > "$WORK/state/pending"
run_post AFTER_HTTP_CHECKS="http://127.0.0.1:$PORT/|200" AFTER_TIMEOUT_SEC=5
{ grep -q "BOOT-OK host=testhost" "$WORK/log" && [ ! -f "$WORK/state/pending" ]; } && ok "부팅 후 확인 통과: BOOT-OK 기록, pending 제거" || bad "부팅 후 확인 통과"
reset; printf 'EPOCH=%s\nKERNEL=old\n' "$(date +%s)" > "$WORK/state/pending"
run_post AFTER_HTTP_CHECKS="http://127.0.0.1:$PORT/|401" AFTER_TIMEOUT_SEC=2
{ grep -q "BOOT-FAIL host=testhost" "$WORK/log" && grep -q "기대401" "$WORK/log" && [ ! -f "$WORK/state/pending" ]; } && ok "기대와 다른 응답: BOOT-FAIL 기록" || bad "부팅 후 확인 실패"
reset
run_post AFTER_HTTP_CHECKS="http://127.0.0.1:$PORT/|401" AFTER_TIMEOUT_SEC=2
{ [ ! -f "$WORK/log" ]; } && ok "자동 재부팅이 아닌 일반 부팅(pending 없음): 아무것도 하지 않음" || bad "일반 부팅"

echo "결과: 통과 $pass, 실패 $fail"
[ "$fail" -eq 0 ]
