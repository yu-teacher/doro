#!/usr/bin/env bash
# 서버(mini)에서 실행한다: 게이트웨이의 loki_upstream 을 다른 Loki(중앙 로그 저장소, 노트북)로 바꾼다. 여러 번 실행해도 결과가 같다(멱등).
# 포털의 관리자 로그 조회 화면은 게이트웨이의 /loki/ 를 거쳐 Loki 를 읽는다. 예전에는 미니의 loki 컨테이너였고, 그 컨테이너를 없애기 전에 이 스크립트로 먼저 옮긴다.
# 게이트웨이는 compose 밖에 있고 nginx.conf 를 파일 하나로 마운트하므로 같은 inode 로 덮어쓴다(mv 로 바꾸면 컨테이너가 옛 파일을 계속 본다).
# 반영 전에 컨테이너 안에서 새 설정을 먼저 검증하고(nginx -t), 실패하면 아무것도 바꾸지 않는다. reload 뒤 확인이 실패하면 백업으로 되돌린다.
# nginx 는 시작·reload 때 업스트림 호스트 이름을 해석하므로, 이름이 사라질(loki 컨테이너 삭제) 상태로 두면 게이트웨이가 못 뜬다. 그래서 IP 로 가리킨다.
#
#   ssh mini 'LOKI_UPSTREAM=192.168.0.4:3100 bash -s -- --dry-run' < scripts/apply-gateway-loki.sh   # 바뀔 내용과 검증만
#   ssh mini 'LOKI_UPSTREAM=192.168.0.4:3100 bash -s -- --apply'   < scripts/apply-gateway-loki.sh   # 반영
set -Eeuo pipefail

CONF="${GATEWAY_CONF:-$HOME/doro/gateway/nginx.conf}"
CONTAINER="${GATEWAY_CONTAINER:-doro-gateway}"
TARGET="${LOKI_UPSTREAM:?LOKI_UPSTREAM 가 필요하다 (예: 192.168.0.4:3100)}"
APPLY=false
for a in "$@"; do
  case "$a" in
    --apply) APPLY=true ;;
    --dry-run) APPLY=false ;;
    *) echo "알 수 없는 옵션: $a" >&2; exit 2 ;;
  esac
done
[[ "$TARGET" =~ ^[A-Za-z0-9._-]+:[0-9]{2,5}$ ]] || { echo "LOKI_UPSTREAM 형식이 이상하다: $TARGET" >&2; exit 2; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
[ -f "$CONF" ] || die "$CONF 가 없다"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "$CONTAINER 컨테이너가 없다"

TS="$(date +%Y%m%d-%H%M%S)"
NEW="$(mktemp)"; trap 'rm -f "$NEW"' EXIT
STATE="$(python3 - "$CONF" "$NEW" "$TARGET" <<'PY'
import re, sys
conf_path, out_path, target = sys.argv[1], sys.argv[2], sys.argv[3]
conf = open(conf_path, encoding="utf-8").read()
blocks = re.findall(r"upstream\s+loki_upstream\s*\{[^}]*\}", conf)
if len(blocks) != 1:
    sys.exit(f"loki_upstream 블록이 정확히 1개여야 한다(찾은 수: {len(blocks)})")
servers = re.findall(r"server\s+([^;]+);", blocks[0])
if servers == [target]:
    print("SAME"); open(out_path, "w", encoding="utf-8").write(conf); sys.exit(0)
new_block = (
    "upstream loki_upstream {\n"
    "        # 중앙 로그 저장소(노트북의 Loki). 포털 로그 조회 화면이 /loki/ 로 읽는다. 이름이 아니라 IP 인 이유: 이름이 사라지면 nginx 가 시작하지 못한다.\n"
    f"        server {target};\n"
    "    }"
)
open(out_path, "w", encoding="utf-8").write(conf.replace(blocks[0], new_block, 1))
print("CHANGE " + (servers[0] if servers else "?"))
PY
)" || die "설정을 만들지 못했다: $STATE"

if [ "$STATE" = "SAME" ]; then log "이미 loki_upstream 이 $TARGET 이다. 변경하지 않는다."; exit 0; fi
log "바꿀 내용: loki_upstream  ${STATE#CHANGE }  ->  $TARGET"
diff <(cat "$CONF") "$NEW" | sed 's/^/    /' || true

# 컨테이너 안에서 검증한다(인증서 경로 등 컨테이너 기준으로 읽는다). 운영 설정은 아직 건드리지 않는다.
docker cp "$NEW" "$CONTAINER:/tmp/nginx.new.conf" >/dev/null
if ! docker exec "$CONTAINER" nginx -t -c /tmp/nginx.new.conf 2>&1 | sed 's/^/    /'; then
  docker exec "$CONTAINER" rm -f /tmp/nginx.new.conf || true
  die "새 설정 검증 실패. 아무것도 바꾸지 않았다"
fi
docker exec "$CONTAINER" rm -f /tmp/nginx.new.conf || true
# 새 업스트림에 게이트웨이 컨테이너가 실제로 닿는지(반영 전에 확인)
docker exec "$CONTAINER" wget -q -T 5 -O /dev/null "http://$TARGET/gateway-health" || die "게이트웨이 컨테이너에서 $TARGET 에 닿지 않는다. 아무것도 바꾸지 않았다"
log "검증 통과: 새 설정 문법 OK, $TARGET 에 도달 가능"

if [ "$APPLY" != true ]; then log "--dry-run 이므로 여기서 종료한다. 반영하려면 --apply"; exit 0; fi

BACKUP="$CONF.bak-loki-$TS"
cp -p "$CONF" "$BACKUP"; log "백업: $BACKUP"
cat "$NEW" > "$CONF"                      # 같은 inode 에 덮어쓴다
restore() { cat "$BACKUP" > "$CONF"; docker exec "$CONTAINER" nginx -s reload >/dev/null 2>&1 || true; log "!! 실패. 백업으로 되돌렸다"; }
docker exec "$CONTAINER" nginx -t >/dev/null 2>&1 || { restore; die "반영 후 nginx -t 실패"; }
docker exec "$CONTAINER" nginx -s reload || { restore; die "reload 실패"; }
sleep 2

# 확인: 사이트가 살아 있고, /loki/ 는 여전히 관리자 인증으로 막혀 있으며(비로그인 401/403), 새 업스트림이 응답한다
code_root="$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H 'Host: localhost' http://127.0.0.1:80/ || true)"
code_loki="$(curl -s -o /dev/null -w '%{http_code}' -m 10 -k https://127.0.0.1/loki/api/v1/labels || true)"
log "확인: 사이트(80)=$code_root, /loki/(비로그인)=$code_loki (401 또는 403 이어야 한다)"
case "$code_loki" in 401|403) ;; *) restore; die "/loki/ 가 비로그인 상태에서 $code_loki 를 돌려준다(인증 보호가 의심된다)" ;; esac
docker exec "$CONTAINER" wget -q -T 5 -O /dev/null "http://$TARGET/gateway-health" || { restore; die "반영 후 업스트림에 닿지 않는다"; }
log "완료. 문제가 있으면 즉시: cat $BACKUP > $CONF && docker exec $CONTAINER nginx -s reload"
