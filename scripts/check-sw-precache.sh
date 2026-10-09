#!/usr/bin/env bash
# 서비스 워커(sw.js)가 미리 캐시하는 모든 주소가 게이트웨이를 거쳐 "이동(3xx) 없이" 올바른 파일로 나가는지 확인한다.
# 서비스 워커는 설치할 때 이 주소들을 모두 받아 저장한다. 게이트웨이가 하나라도 다른 곳으로 이동시키면 엉뚱한 화면(예: 허브 앱 셸 자리에
# 블로그 화면)이 저장되고, 설치된 기기에는 그 오류가 남아 되돌리기 어렵다(2026-10-09 허브 빈 화면 사고).
#
#   scripts/check-sw-precache.sh <dist 폴더 | image:<이미지>[:<이미지 안 경로>]> <공개 접두 경로> [pre|post]
#     공개 접두 경로: sw.js 가 공개 주소에서 놓이는 폴더. 허브 '/', 블로그 '/blog/', 도로메뉴 '/menu/'.
#     pre  (배포 전): 새 해시 파일은 아직 서버에 없으니 200 또는 404 를 허용한다. 이동(3xx)·서버 오류·연결 실패는 실패.
#     post (배포 후): 전부 200 이어야 하고, 정적 파일(js/css/png/svg/webmanifest)이 HTML(text/html)로 오면 실패(SPA 폴백이 가린 오류).
#   환경변수: GATEWAY_URL(기본 https://127.0.0.1), CHECK_TIMEOUT_SEC(기본 10)
#
# 같은 파일이 doro-menu/scripts/ 에도 있다(메뉴 저장소는 Doro 체크아웃이 없어 복사해 둔다). 고치면 양쪽을 함께 바꾼다.
set -Eeuo pipefail

SRC="${1:?dist 폴더 또는 image:<이미지> 가 필요하다}"
PREFIX="${2:?공개 접두 경로가 필요하다(예: / , /blog/ , /menu/)}"
PHASE="${3:-pre}"
GATEWAY_URL="${GATEWAY_URL:-https://127.0.0.1}"
TIMEOUT="${CHECK_TIMEOUT_SEC:-10}"
case "$PHASE" in pre|post) ;; *) echo "단계는 pre 또는 post: $PHASE" >&2; exit 2 ;; esac
[[ "$PREFIX" == /* ]] || { echo "공개 접두 경로는 /로 시작해야 한다: $PREFIX" >&2; exit 2; }
[[ "$PREFIX" == */ ]] || PREFIX="$PREFIX/"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

TMP=""; cleanup() { [ -z "$TMP" ] || rm -rf "$TMP"; }; trap cleanup EXIT
DIST="$SRC"
if [[ "$SRC" == image:* ]]; then
  spec="${SRC#image:}"; img="${spec%%:/*}"; path="/${spec#*:/}"; [ "$img" != "$spec" ] || path="/usr/share/nginx/html"
  TMP="$(mktemp -d)"; cid="$(docker create "$img")"
  docker cp "$cid:$path/." "$TMP/" >/dev/null; docker rm "$cid" >/dev/null
  DIST="$TMP"
fi
[ -f "$DIST/sw.js" ] || { log "서비스 워커가 없다: $DIST/sw.js (PWA 가 아닌 빌드면 건너뛴다)"; exit 0; }

# sw.js 에서 미리 캐시 목록(url:"..."), 앱 셸 폴백(createHandlerBoundToURL("...")), workbox 런타임 파일을 뽑는다. sw.js 자신도 포함한다.
PY_FILE="$(mktemp)"
cat > "$PY_FILE" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
prefix = sys.argv[2]
workbox = [name + ".js" for name in re.findall(r"(workbox-[A-Za-z0-9_-]+?)(?:\.js)?[\"']", text)]
found = re.findall(r'url:"([^"]+)"', text) + re.findall(r'createHandlerBoundToURL\("([^"]+)"\)', text) + workbox + ["sw.js"]
seen = []
for u in found:
    path = u if u.startswith("/") else prefix + u.lstrip("./")
    if path not in seen:
        seen.append(path)
print("\n".join(seen))
PY
LIST="$(python3 "$PY_FILE" "$DIST/sw.js" "$PREFIX")"; rm -f "$PY_FILE"
PATHS=()
while IFS= read -r line; do [ -z "$line" ] || PATHS+=("$line"); done <<< "$LIST"
[ "${#PATHS[@]}" -gt 0 ] || { log "ERROR: sw.js 에서 미리 캐시 목록을 찾지 못했다(형식이 바뀌었는지 확인)"; exit 1; }
log "서비스 워커 미리 캐시 점검(${PHASE}): ${#PATHS[@]}개, 게이트웨이 $GATEWAY_URL, 접두 $PREFIX"

bad=0
for p in "${PATHS[@]}"; do
  out="$(curl -sk -m "$TIMEOUT" --max-redirs 0 -o /dev/null -w '%{http_code} %{content_type} %{redirect_url}' "$GATEWAY_URL$p" || true)"
  code="${out%% *}"; rest="${out#* }"; ctype="${rest%% *}"; redirect="${rest#* }"
  ok=true; why=""
  case "$code" in
    200) if [ "$PHASE" = post ] && [[ "$p" =~ \.(js|css|png|svg|webmanifest)$ ]] && [[ "$ctype" == text/html* ]]; then ok=false; why="정적 파일이 HTML 로 왔다(SPA 폴백이 가린 오류)"; fi ;;
    404) if [ "$PHASE" = post ]; then ok=false; why="배포 후에도 404"; fi ;;
    3??) ok=false; why="이동(${code}) → ${redirect#*://*/}: 서비스 워커가 엉뚱한 화면을 저장한다" ;;
    *)   ok=false; why="응답 ${code:-없음}" ;;
  esac
  if [ "$ok" = true ]; then printf '  OK   %s -> %s\n' "$p" "$code"; else printf '  FAIL %s -> %s %s\n' "$p" "$code" "$why"; bad=$((bad + 1)); fi
done
if [ "$bad" -gt 0 ]; then log "ERROR: 미리 캐시 주소 $bad 개가 올바르지 않다. 게이트웨이 라우팅을 고치기 전에는 배포하지 않는다(docs/GATEWAY_ROUTING_RULES.md 의 허브 PWA 파일 규칙 참고)."; exit 1; fi
log "미리 캐시 주소 전부 정상(${PHASE})"
