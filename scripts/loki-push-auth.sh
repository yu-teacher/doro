#!/usr/bin/env bash
# 중앙 Loki 게이트웨이의 push 에 Basic 인증을 켜고 끈다. 노트북(~/central-logs 가 있는 서버)에서 실행한다. 비밀번호는 화면에 출력하지 않는다.
#
#   ssh notebook 'bash -s -- --check'    < scripts/loki-push-auth.sh   # 읽기 전용: 현재 상태
#   ssh notebook 'bash -s -- --generate' < scripts/loki-push-auth.sh   # 계정 파일·비밀번호 생성(켜지 않는다)
#   ssh notebook 'bash -s -- --verify'   < scripts/loki-push-auth.sh   # 호스트별 로그가 최근에 들어왔는지 확인
#   ssh notebook 'bash -s -- --enable'   < scripts/loki-push-auth.sh   # 인증 켜기(게이트웨이 재생성)
#   ssh notebook 'bash -s -- --disable'  < scripts/loki-push-auth.sh   # 인증 끄기(되돌리기)
#
# 순서(이 순서를 지킨다. 인증이 켜진 뒤 비밀번호가 틀린 수집기의 로그는 재시도 없이 버려진다 — Alloy 는 401 을 재시도하지 않는다)
#   1) 새 설정 파일을 서버에 올린다: central-logs(nginx-loki.conf.template, docker-compose.yml), log-shipper(docker-compose.yml, conf.d/host.alloy)
#   2) --generate 로 계정을 만든다
#   3) 계정을 mini 와 pi 의 ~/log-shipper/.env 로 옮기고 수집기를 다시 띄운다(아래 한 줄 명령, 비밀번호는 파이프로만 이동):
#        ssh notebook "grep -E '^LOKI_PUSH_(USER|PASSWORD)=' ~/central-logs/.env" | ssh mini 'f=~/log-shipper/.env; grep -vE "^LOKI_PUSH_(USER|PASSWORD)=" $f > $f.new; cat >> $f.new; chmod 600 $f.new; mv $f.new $f'
#        ssh mini 'cd ~/log-shipper && docker compose --env-file .env up -d'     (pi 도 같은 방식)
#   4) --enable
#   5) --verify (몇 분 뒤). 로그가 끊긴 호스트가 있으면 --disable 로 바로 되돌리고 원인을 본다.
# 게이트웨이 인증이 꺼져 있는 동안(기본)에는 수집기가 보내는 계정을 서버가 무시하므로 3)을 먼저 해도 안전하다.
set -Eeuo pipefail

MODE_ARG="${1:---check}"
CENTRAL_DIR="${CENTRAL_DIR:-$HOME/central-logs}"
ENV_FILE="$CENTRAL_DIR/.env"
AUTH_DIR="$CENTRAL_DIR/auth"
HTPASSWD="$AUTH_DIR/push.htpasswd"
GATEWAY_SERVICE=loki-gateway
LOKI_LOCAL="http://127.0.0.1:${LOKI_LOCAL_PORT:-3101}"
REALM="Doro Loki push"
USER_NAME="doro-shipper"
FRESH_MINUTES="${FRESH_MINUTES:-10}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
get_var() { grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- || true; }
set_var() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "$file.XXXXXX")"
  grep -v "^$key=" "$file" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  chmod 600 "$tmp"; mv "$tmp" "$file"
}
compose() { ( cd "$CENTRAL_DIR" && docker compose --env-file .env "$@" ); }
gateway_state() { docker ps --filter "name=loki-gateway" --format '{{.Names}} {{.Status}}' | head -1; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"

describe() {
  local on; on="$(get_var "$ENV_FILE" LOKI_PUSH_AUTH)"
  log "게이트웨이 인증(LOKI_PUSH_AUTH): ${on:-off(기본)}"
  log "계정 파일: $([ -s "$HTPASSWD" ] && echo '있음' || echo '없음')   .env 의 계정: $([ -n "$(get_var "$ENV_FILE" LOKI_PUSH_PASSWORD)" ] && echo '있음' || echo '없음')"
  log "게이트웨이 컨테이너: $(gateway_state)"
  log "게이트웨이 설정이 새 정책(허용 경로 목록)인가: $(docker exec "$(docker ps --filter name=loki-gateway -q | head -1)" grep -c 'return 403' /etc/nginx/conf.d/default.conf 2>/dev/null || echo 0)"
}

# 호스트별로 최근 FRESH_MINUTES 분 안에 로그가 들어왔는지. 중앙 Loki 에 직접(노트북 안에서) 묻는다.
verify() {
  local hosts h n rc=0
  hosts="$(curl -s -m 10 "$LOKI_LOCAL/loki/api/v1/label/host/values" | python3 -c 'import sys,json; print(" ".join(json.load(sys.stdin).get("data",[])))' 2>/dev/null || true)"
  [ -n "$hosts" ] || die "Loki 에서 호스트 목록을 읽지 못했다($LOKI_LOCAL)"
  for h in $hosts; do
    n="$(curl -s -G -m 10 "$LOKI_LOCAL/loki/api/v1/query" --data-urlencode "query=sum(count_over_time({host=\"$h\"}[${FRESH_MINUTES}m]))" \
        | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)' 2>/dev/null || echo 0)"
    if [ "$n" -gt 0 ]; then log "  $h: 최근 ${FRESH_MINUTES}분 로그 $n 줄 ✅"; else log "  $h: 최근 ${FRESH_MINUTES}분 로그 없음 ❌"; rc=1; fi
  done
  return $rc
}

case "$MODE_ARG" in
  --check) describe; exit 0 ;;
  --verify) verify; exit $? ;;
  --generate)
    [ ! -e "$HTPASSWD" ] || die "이미 계정 파일이 있다($HTPASSWD). 바꾸려면 파일과 .env 의 LOKI_PUSH_* 를 지운 뒤 다시 실행하고, 수집기에 새 비밀번호를 옮긴다"
    mkdir -p "$AUTH_DIR"
    PW="$(openssl rand -hex 24)"
    printf '%s:%s\n' "$USER_NAME" "$(openssl passwd -apr1 "$PW")" > "$HTPASSWD"
    chmod 644 "$HTPASSWD"   # 컨테이너 안의 nginx(비 root 워커)가 읽어야 한다. 파일에는 해시만 있다
    set_var "$ENV_FILE" LOKI_PUSH_USER "$USER_NAME"
    set_var "$ENV_FILE" LOKI_PUSH_PASSWORD "$PW"; unset PW
    log "계정을 만들었다(켜지 않음). 다음: 수집기(mini, pi)로 계정을 옮긴다 — 스크립트 머리말 3) 참고."
    ;;
  --enable)
    [ -s "$HTPASSWD" ] || die "계정 파일이 없다. 먼저 --generate"
    [ -n "$(get_var "$ENV_FILE" LOKI_PUSH_PASSWORD)" ] || die ".env 에 LOKI_PUSH_PASSWORD 가 없다. 먼저 --generate"
    set_var "$ENV_FILE" LOKI_PUSH_AUTH "$REALM"
    compose up -d "$GATEWAY_SERVICE" >/dev/null
    sleep 3
    [ "$(docker inspect "$(docker ps --filter name=loki-gateway -q | head -1)" --format '{{.State.Status}}')" = running ] || { log "게이트웨이가 올라오지 않았다 — 되돌린다"; set_var "$ENV_FILE" LOKI_PUSH_AUTH off; compose up -d "$GATEWAY_SERVICE" >/dev/null; die "되돌렸다"; }
    log "켰다. 몇 분 뒤 --verify 로 호스트별 로그가 계속 들어오는지 확인한다(끊기면 --disable)."
    ;;
  --disable)
    set_var "$ENV_FILE" LOKI_PUSH_AUTH off
    compose up -d "$GATEWAY_SERVICE" >/dev/null
    log "껐다(IP 허용만 검사). 수집기 계정은 그대로 두어도 된다."
    ;;
  *) echo "사용법: bash -s -- --check|--generate|--enable|--disable|--verify" >&2; exit 2 ;;
esac
