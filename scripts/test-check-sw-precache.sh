#!/usr/bin/env bash
# check-sw-precache.sh 가 사고 상황(앱 셸이 /blog 로 이동)을 실제로 잡는지 시험한다. 작은 가짜 게이트웨이(python)를 띄워 응답을 바꿔 가며 확인한다.
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$HERE/check-sw-precache.sh"
WORK="$(mktemp -d)"; PORT="${TEST_PORT:-18765}"; SRV_PID=""
cleanup() { [ -z "$SRV_PID" ] || kill "$SRV_PID" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

mkdir -p "$WORK/dist"
cat > "$WORK/dist/sw.js" <<'JS'
define(["./workbox-abc123"],function(e){});precacheAndRoute([{url:"registerSW.js",revision:"1"},{url:"index.html",revision:"2"},{url:"assets/index-AAA.js",revision:null},{url:"manifest.webmanifest",revision:"3"}]);registerRoute(new NavigationRoute(createHandlerBoundToURL("index.html")));
JS

cat > "$WORK/server.py" <<'PY'
import os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
MODE = os.environ["MODE"]
TYPES = {".js": "text/javascript", ".webmanifest": "application/manifest+json", ".html": "text/html"}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        path = self.path.split("?")[0]
        if MODE == "redirect-shell" and path == "/index.html":
            self.send_response(302); self.send_header("Location", "/blog/index.html"); self.end_headers(); return
        if MODE == "missing" and path == "/assets/index-AAA.js":
            self.send_response(404); self.end_headers(); return
        if MODE == "html-fallback" and path == "/assets/index-AAA.js":
            body = b"<html></html>"; ctype = "text/html"
        else:
            body = b"x"; ctype = TYPES.get(os.path.splitext(path)[1], "text/plain")
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY

pass=0; fail=0
expect() { # <설명> <기대 종료코드 0|1> <MODE> <phase> [출력에 있어야 할 문구]
  local label="$1" want="$2" mode="$3" phase="$4" must="${5:-}" out rc=0
  MODE="$mode" python3 "$WORK/server.py" "$PORT" & SRV_PID=$!
  sleep 0.7
  out="$(GATEWAY_URL="http://127.0.0.1:$PORT" bash "$CHECK" "$WORK/dist" / "$phase" 2>&1)" || rc=$?
  kill "$SRV_PID" 2>/dev/null || true; wait "$SRV_PID" 2>/dev/null || true; SRV_PID=""
  if [ "$rc" = "$want" ] && { [ -z "$must" ] || printf '%s' "$out" | grep -qF -- "$must"; }; then
    pass=$((pass + 1)); printf '  통과  %s\n' "$label"
  else
    fail=$((fail + 1)); printf '  실패  %s (종료코드 %s, 기대 %s)\n' "$label" "$rc" "$want"; printf '%s\n' "$out" | sed 's/^/        /'
  fi
}
expect "정상: 배포 전 점검 통과"                                   0 ok pre
expect "정상: 배포 후 점검 통과"                                   0 ok post
expect "사고 재현: 앱 셸이 /blog 로 이동하면 배포 전에 실패"       1 redirect-shell pre "/index.html -> 302"
expect "새 해시 파일이 아직 없어도(404) 배포 전에는 통과"          0 missing pre
expect "배포 후에도 파일이 없으면(404) 실패"                       1 missing post "배포 후에도 404"
expect "배포 후 정적 파일이 HTML 로 오면(SPA 폴백) 실패"           1 html-fallback post "HTML"
echo "결과: 통과 $pass, 실패 $fail"
[ "$fail" -eq 0 ]
