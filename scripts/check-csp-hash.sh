#!/usr/bin/env bash
# 각 앱 index.html 의 인라인 <script> 해시가 gateway/nginx.conf 의 CSP script-src 에 등록돼 있는지 검증한다.
# 인라인 스크립트를 바꾸고 CSP 해시를 갱신하지 않으면 운영에서 그 스크립트가 조용히 차단되므로, 배포 전에 실패시킨다.
#
#   scripts/check-csp-hash.sh                      # 이 저장소의 web/index.html 만 검사
#   scripts/check-csp-hash.sh web/index.html ../doro-blog/web/index.html ../doro-menu/index.html
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="${CSP_CONF:-$ROOT/gateway/nginx.conf}"
FILES=("$@")
[ ${#FILES[@]} -gt 0 ] || FILES=("$ROOT/web/index.html")

[ -f "$CONF" ] || { echo "ERROR: $CONF 가 없다" >&2; exit 1; }
status=0
for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "SKIP  $f (없음)"; continue; }
  result="$(python3 - "$f" "$CONF" <<'PY'
import base64, hashlib, re, sys
html = open(sys.argv[1], encoding="utf-8").read()
conf = open(sys.argv[2], encoding="utf-8").read()
scripts = re.findall(r"<script>(.*?)</script>", html, re.S)  # src 가 없는 인라인 스크립트만
missing = []
for body in scripts:
    digest = "sha256-" + base64.b64encode(hashlib.sha256(body.encode()).digest()).decode()
    if f"'{digest}'" not in conf:
        missing.append(digest)
print("OK" if not missing else "MISSING " + " ".join(missing), len(scripts))
PY
)"
  case "$result" in
    OK*) echo "OK    $f (인라인 스크립트 ${result#OK } 개, 해시 등록됨)" ;;
    *)   echo "FAIL  $f -> ${result%% [0-9]*}" >&2; status=1 ;;
  esac
done
[ $status -eq 0 ] || echo "gateway/nginx.conf 의 Content-Security-Policy script-src 에 위 해시를 추가하고 서버 게이트웨이에도 반영한다." >&2
exit $status
