#!/usr/bin/env bash
# 서버(mini)에서 실행한다: 게이트웨이(nginx.conf)에서 블로그를 /blog/ 아래로 옮긴다. 변환은 gateway_blog_switch.py 가 한다(같은 코드를 로컬 시험에도 쓴다).
#   단계 1: /blog, /blog/api/ 경로를 "추가"한다. 기존 경로는 그대로라 사용자에게 보이는 변화가 없다.
#   단계 2: / 를 허브(포털)로, 나머지 알 수 없는 경로를 /blog 로 임시 이동(302)시키고, Referer 추측 두 곳의 기본값을 포털/IAM 으로 바꾼다.
# 단계 2 는 블로그가 VITE_BASE_PATH=/blog 로 다시 배포된 뒤에만 적용한다(안 그러면 /blog/ 화면의 에셋이 깨진다).
#
# 게이트웨이는 compose 밖에 있고 nginx.conf 를 파일 하나로 마운트하므로 같은 inode 로 덮어쓴다. 반영 전에 컨테이너 안에서 새 설정을 검증하고(nginx -t),
# 실패하면 아무것도 바꾸지 않는다. reload 뒤 단계별 확인이 하나라도 어긋나면 백업으로 되돌린다.
#
#   scp scripts/gateway_blog_switch.py mini:gateway_blog_switch.py
#   ssh mini 'bash -s -- --stage 1 --dry-run' < scripts/apply-gateway-blog.sh   # 바뀔 내용과 검증만
#   ssh mini 'bash -s -- --stage 1 --apply'   < scripts/apply-gateway-blog.sh   # 반영
#   ssh mini 'bash -s -- --stage 2 --apply'   < scripts/apply-gateway-blog.sh
set -Eeuo pipefail

CONF="${GATEWAY_CONF:-$HOME/doro/gateway/nginx.conf}"
CONTAINER="${GATEWAY_CONTAINER:-doro-gateway}"
TRANSFORM="${GATEWAY_TRANSFORM:-$HOME/gateway_blog_switch.py}"
STAGE=""; APPLY=false
while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGE="${2:-}"; shift 2 ;;
    --apply) APPLY=true; shift ;;
    --dry-run) APPLY=false; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done
[ "$STAGE" = 1 ] || [ "$STAGE" = 2 ] || { echo "--stage 1 또는 --stage 2 가 필요하다" >&2; exit 2; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
[ -f "$CONF" ] || die "$CONF 가 없다"
[ -f "$TRANSFORM" ] || die "$TRANSFORM 가 없다 (scp scripts/gateway_blog_switch.py mini:gateway_blog_switch.py 로 먼저 복사한다)"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "$CONTAINER 컨테이너가 없다"
command -v python3 >/dev/null || die "python3 가 필요하다"

TS="$(date +%Y%m%d-%H%M%S)"
NEW="$(mktemp)"; trap 'rm -f "$NEW"' EXIT
RESULT="$(python3 "$TRANSFORM" "$CONF" "$NEW" "$STAGE")" || die "설정을 만들지 못했다(서버 설정이 예상과 다르면 건드리지 않는다)"
if [ "$RESULT" = "SAME $STAGE" ]; then log "단계 $STAGE 는 이미 적용돼 있다. 변경하지 않는다."; exit 0; fi
log "단계 $STAGE 로 바뀔 내용:"
diff "$CONF" "$NEW" | sed 's/^/    /' || true

# 컨테이너 안에서 검증한다(인증서 경로 등 컨테이너 기준). 운영 설정은 아직 건드리지 않는다.
docker cp "$NEW" "$CONTAINER:/tmp/nginx.new.conf" >/dev/null
if ! docker exec "$CONTAINER" nginx -t -c /tmp/nginx.new.conf 2>&1 | sed 's/^/    /'; then
  docker exec "$CONTAINER" rm -f /tmp/nginx.new.conf || true
  die "새 설정 검증 실패. 아무것도 바꾸지 않았다"
fi
docker exec "$CONTAINER" rm -f /tmp/nginx.new.conf || true
log "검증 통과: 새 설정 문법 OK"
if [ "$APPLY" != true ]; then log "--dry-run 이므로 여기서 종료한다(아무것도 바꾸지 않았다). 반영하려면 --apply"; exit 0; fi

BACKUP="$CONF.bak-blog-stage$STAGE-$TS"
cp -p "$CONF" "$BACKUP"; log "백업: $BACKUP"
cat "$NEW" > "$CONF"                      # 같은 inode 에 덮어쓴다
restore() { cat "$BACKUP" > "$CONF"; docker exec "$CONTAINER" nginx -s reload >/dev/null 2>&1 || true; log "!! 실패. 백업으로 되돌렸다: $BACKUP"; }
docker exec "$CONTAINER" nginx -t >/dev/null 2>&1 || { restore; die "반영 후 nginx -t 실패"; }
docker exec "$CONTAINER" nginx -s reload || { restore; die "reload 실패"; }
sleep 2

# 확인: 게이트웨이(443)에 직접 요청해 상태 코드와 이동 위치를 본다. 하나라도 어긋나면 되돌린다.
FAILED=()
check() { # <설명> <기대 상태> <경로> [기대 Location(경로만)]
  local label="$1" want="$2" path="$3" want_loc="${4:-}" out code loc
  out="$(curl -sk -m 10 -o /dev/null -D - -w 'CODE:%{http_code}\n' "https://127.0.0.1$path" | tr -d '\r')"
  code="$(printf '%s\n' "$out" | sed -n 's/^CODE://p')"
  loc="$(printf '%s\n' "$out" | sed -n 's/^[Ll]ocation: //p' | head -1 | sed -E 's#^https?://[^/]+##')"
  if [ "$code" = "$want" ] && { [ -z "$want_loc" ] || [ "$loc" = "$want_loc" ]; }; then
    log "  OK   $label: $path -> $code${loc:+ $loc}"
  else
    log "  FAIL $label: $path -> $code${loc:+ $loc} (기대 $want${want_loc:+ $want_loc})"; FAILED+=("$label")
  fi
}
log "확인(단계 $STAGE):"
# 공통: 다른 서비스와 인증 보호는 어느 단계에서도 그대로여야 한다.
check "포털 로그인 화면" 200 /login
check "OAuth 동의 화면" 200 /oauth2/consent
check "OIDC 설정" 200 /.well-known/openid-configuration
check "Loki 보호(비로그인)" 401 /loki/api/v1/labels
check "도로 파티" 200 /party/
check "도로 게임" 200 /games/
check "도로메뉴" 200 /menu/
check "블로그 API(옛 주소, 호환)" 200 "/api/v1/posts?page=0&size=1"
if [ "$STAGE" = 1 ]; then
  check "블로그(옛 주소, 아직 루트)" 200 /
  check "새 경로 /blog/ 가 열린다" 200 /blog/
  check "/blog 는 /blog/ 로" 302 /blog /blog/
  check "블로그 API(새 주소)" 200 "/blog/api/v1/posts?page=0&size=1"
else
  check "메인은 허브" 200 /
  check "옛 블로그 주소는 /blog 로" 302 /@doro /blog/@doro
  check "옛 주소의 쿼리 문자열 유지" 302 "/tags?tag=java" "/blog/tags?tag=java"
  check "블로그(새 주소)" 200 /blog/
  check "블로그 API(새 주소)" 200 "/blog/api/v1/posts?page=0&size=1"
  check "robots.txt" 200 /robots.txt
fi
if [ "${#FAILED[@]}" -gt 0 ]; then restore; die "확인 실패: ${FAILED[*]}"; fi
log "완료(단계 $STAGE). 문제가 있으면 즉시: cat $BACKUP > $CONF && docker exec $CONTAINER nginx -s reload"
