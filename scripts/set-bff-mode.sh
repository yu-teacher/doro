#!/usr/bin/env bash
# 서비스(blog|party|games)의 로그인 방식을 바꾼다: embedded(서비스 안의 BFF, 기본) ↔ gateway(별도 doro-bff + 게이트웨이 auth_request).
# 운영 서버(mini)에서 실행한다. 서비스의 서버 쪽 docker-compose.prod.yml 과 .env 를 고치고(백업 후) 그 서비스 컨테이너만 다시 만든다.
#
#   ssh mini 'bash -s -- party --check'    < scripts/set-bff-mode.sh   # 읽기 전용: 현재 모드와 준비 상태
#   ssh mini 'bash -s -- party --gateway'  < scripts/set-bff-mode.sh   # gateway 로 전환(사용자는 한 번 다시 로그인한다)
#   ssh mini 'bash -s -- party --embedded' < scripts/set-bff-mode.sh   # 되돌리기(사용자는 한 번 다시 로그인한다)
#
# --gateway 의 사전 점검(하나라도 안 맞으면 아무것도 바꾸지 않고 중단한다)
#   - doro-bff-api 가 healthy
#   - 게이트웨이(doro-gateway)에 그 서비스의 BFF 라우트가 적용돼 있다(scripts/apply-gateway-conf.sh 로 반영한 뒤)
#   - 실행 중인 서비스 이미지가 모드 스위치를 포함한다(코드를 먼저 배포)
# 전환 뒤 서비스가 healthy 로 돌아오지 않으면 compose/.env 를 되돌리고 다시 만든다.
set -Eeuo pipefail

SVC="${1:-}"; MODE_ARG="${2:---check}"
case "$SVC" in
  blog)  DIR="$HOME/doro-blog";  SERVICE=blog-backend; CONTAINER=doro-blog-backend; VAR=BLOG_AUTH_MODE;  KEYVAR=BLOG_SESSION_KEY;  LOGIN=/dev/blog/api/v1/bff/login ;;
  party) DIR="$HOME/doro-party"; SERVICE=party-api;    CONTAINER=doro-party-api;    VAR=PARTY_AUTH_MODE; KEYVAR=PARTY_SESSION_KEY; LOGIN=/party/api/v1/bff/login ;;
  games) DIR="$HOME/doro-games"; SERVICE=games-api;    CONTAINER=doro-games-api;    VAR=GAMES_AUTH_MODE; KEYVAR=GAMES_SESSION_KEY; LOGIN=/games/api/v1/bff/login ;;
  *) echo "사용법: bash -s -- blog|party|games --check|--gateway|--embedded" >&2; exit 2 ;;
esac
COMPOSE="$DIR/docker-compose.prod.yml"
ENV_FILE="$DIR/.env"
GATEWAY=doro-gateway
BFF=doro-bff-api

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
healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 60); do healthy "$1" && return 0; sleep 3; done; return 1; }
recreate() { ( cd "$DIR" && docker compose -f docker-compose.prod.yml up -d --no-build --no-deps "$SERVICE" ) >/dev/null; }
current_mode() { local m; m="$(get_var "$ENV_FILE" $VAR)"; printf '%s' "${m:-embedded}"; }
compose_passes_var() { grep -q "$VAR" "$COMPOSE"; }
image_has_switch() { [ "$(docker exec "$CONTAINER" sh -c 'unzip -l /app/app.jar 2>/dev/null | grep -c ConditionalOnEmbeddedBff' 2>/dev/null | head -1)" -ge 1 ] 2>/dev/null; }
gateway_has_route() { docker exec "$GATEWAY" grep -q "bff/$SVC/login" /etc/nginx/nginx.conf 2>/dev/null; }
login_probe() { curl -sk -o /dev/null -w '%{http_code}' -m 8 "https://127.0.0.1$LOGIN" 2>/dev/null || true; }

[ -f "$COMPOSE" ] && [ -f "$ENV_FILE" ] || die "$COMPOSE 또는 $ENV_FILE 이 없다"

describe() {
  log "서비스 $SVC: 현재 모드 = $(current_mode) (환경변수 $VAR, compose 가 전달: $(compose_passes_var && echo 예 || echo 아니오))"
  log "$KEYVAR: $([ -n "$(get_var "$ENV_FILE" $KEYVAR)" ] && echo '있음(서비스가 계속 필요로 한다)' || echo '없음')"
  log "컨테이너 $CONTAINER: $(docker inspect "$CONTAINER" --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음), 모드 스위치 포함: $(image_has_switch && echo 예 || echo 아니오)"
  log "doro-bff-api: $(docker inspect "$BFF" --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음)"
  log "게이트웨이에 $SVC BFF 라우트: $(gateway_has_route && echo 있음 || echo 없음), 로그인 시작 응답: $(login_probe)"
}

switch_to() {
  local target="$1" ts bak_c bak_e
  ts="$(date +%Y%m%d-%H%M%S)"; bak_c="$COMPOSE.bak-bffmode-$ts"; bak_e="$ENV_FILE.bak-bffmode-$ts"
  cp -p "$COMPOSE" "$bak_c"; cp -p "$ENV_FILE" "$bak_e"; log "백업: $bak_c , $bak_e"
  if ! compose_passes_var; then
    # 세션 키 줄 바로 앞에 모드 변수를 전달하는 줄을 넣는다(들여쓰기는 그 줄을 따른다)
    awk -v key="$KEYVAR" -v var="$VAR" '
      $0 ~ "^[ ]+" key ":" && !done { match($0, /^[ ]+/); ind=substr($0, 1, RLENGTH); print ind var ": ${" var ":-embedded}"; done=1 }
      { print }' "$bak_c" > "$COMPOSE"
    compose_passes_var || { cp -p "$bak_c" "$COMPOSE"; die "compose 에 $KEYVAR 줄이 없어 $VAR 를 넣지 못했다(되돌렸다)"; }
    log "compose 에 $VAR 전달 줄을 추가했다"
  fi
  set_var "$ENV_FILE" $VAR "$target"
  recreate
  if wait_healthy "$CONTAINER"; then
    log "$SVC 가 $target 모드로 healthy. 로그인 시작 응답: $(login_probe)"
    return 0
  fi
  log "$CONTAINER 가 healthy 로 돌아오지 않았다 — 되돌린다"
  cp -p "$bak_c" "$COMPOSE"; cp -p "$bak_e" "$ENV_FILE"; recreate || true
  wait_healthy "$CONTAINER" || log "되돌린 뒤에도 healthy 가 아니다. docker logs $CONTAINER 를 확인한다"
  die "되돌렸다(백업은 그대로 있다)"
}

case "$MODE_ARG" in
  --check) describe ;;
  --gateway)
    [ "$(current_mode)" != gateway ] || { log "이미 gateway 모드다."; exit 0; }
    [ -n "$(get_var "$ENV_FILE" $KEYVAR)" ] || die "$KEYVAR 가 .env 에 없다"
    healthy "$BFF" || die "$BFF 가 healthy 가 아니다. 먼저 BFF 를 배포한다(scripts/enable-bff.sh --check)"
    gateway_has_route || die "게이트웨이에 $SVC 의 BFF 라우트가 없다. 먼저 게이트웨이 설정을 반영한다(scripts/apply-gateway-conf.sh)"
    image_has_switch || die "실행 중인 $CONTAINER 이미지에 모드 스위치가 없다. 서비스 코드를 먼저 배포한다"
    switch_to gateway
    ;;
  --embedded)
    [ "$(current_mode)" != embedded ] || { log "이미 embedded 모드다."; exit 0; }
    switch_to embedded
    ;;
  *) echo "사용법: bash -s -- $SVC --check|--gateway|--embedded" >&2; exit 2 ;;
esac
