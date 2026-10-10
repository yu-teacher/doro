#!/usr/bin/env bash
# Guard 호출자별 토큰에 "소유 네임스페이스"를 붙인다: blog 는 blog_*, party 는 party_*, games 는 games_* 만 쓰기/삭제/스키마 변경.
# (auth 처럼 네임스페이스가 없는 호출자는 제한 없음.) 운영 서버(mini)에서 실행한다. 토큰 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --check'    < scripts/set-guard-namespaces.sh   # 읽기 전용 점검
#   ssh mini 'bash -s -- --apply'    < scripts/set-guard-namespaces.sh   # 적용 (실패하면 자동으로 되돌린다)
#   ssh mini 'bash -s -- --rollback' < scripts/set-guard-namespaces.sh   # 가장 최근 백업(.env.bak-namespaces-*)으로 되돌린다
#
# 선행 조건: 네임스페이스 제한을 지원하는 Guard 가 이미 배포돼 있어야 한다(코드 "per-caller namespaces").
#            낡은 Guard 는 4번째 칸을 알 수 없는 권한으로 보고 기동에 실패하며, 이 스크립트는 그때 자동으로 되돌린다.
# 검증(데이터를 쓰지 않는다)
#   - 소유하지 않은 네임스페이스(system)에 쓰려는 요청이 각 서비스 토큰으로 403 인지
#   - 네임스페이스가 없는 호출자(auth) 토큰으로 조회가 200 인지, 서비스 토큰으로 조회도 200 인지
#   - guard/auth/blog/party/games 컨테이너와 각 서비스 헬스
set -Eeuo pipefail

MODE_ARG="${1:---check}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
ENV_FILE="$DORO_DIR/.env"
GUARD=doro-guard-api
GUARD_URL="${GUARD_URL:-http://127.0.0.1:8081}"
# 서비스 이름 -> 소유 네임스페이스. 새 서비스를 붙이면 여기에 한 줄을 더한다.
NAMESPACES="${NAMESPACES:-blog:blog_*,party:party_*,games:games_*}"
CHECK_BODY='{"namespace":"system","objectId":"doro","relation":"admin","subjectNamespace":"user","subjectId":"00000000-0000-0000-0000-000000000000"}'
# 소유하지 않은 네임스페이스에 쓰려는 요청. 403 으로 거부돼야 하며, 만약 통과하면 데이터가 생기므로 곧바로 실패 처리하고 직접 지워야 한다.
PROBE_BODY='[{"namespace":"system","objectId":"namespace-probe","relation":"admin","subjectNamespace":"user","subjectId":"00000000-0000-0000-0000-000000000000"}]'

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
wait_healthy() { local i; for i in $(seq 1 50); do healthy "$1" && return 0; sleep 3; done; return 1; }
recreate_guard() { ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps guard-api ) >/dev/null; }
http() { curl -s -m 8 -o /dev/null -w '%{http_code}' "$@"; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"
SPEC="$(get_var "$ENV_FILE" DORO_GUARD_SERVICE_TOKENS)"
[ -n "$SPEC" ] || die "DORO_GUARD_SERVICE_TOKENS 가 비어 있다. 먼저 split-guard-tokens.sh --apply 로 호출자별 토큰을 만든다"
GUARD_MODE="$(get_var "$ENV_FILE" DORO_GUARD_SECURITY_MODE)"; GUARD_MODE="${GUARD_MODE:-OFF}"

# SPEC 의 각 항목(이름:토큰[:권한[:네임스페이스]])에 NAMESPACES 의 소유 네임스페이스를 붙인다. 이미 4번째 칸이 있으면 그대로 둔다.
build_spec() {
  printf '%s' "$SPEC" | awk -F, -v map="$NAMESPACES" '
    BEGIN { n = split(map, m, ","); for (i = 1; i <= n; i++) { split(m[i], kv, ":"); ns[kv[1]] = kv[2] } }
    { for (i = 1; i <= NF; i++) {
        k = split($i, p, ":")
        if (k <= 3 && (p[1] in ns)) { scope = (k == 3 ? p[3] : ""); $i = p[1] ":" p[2] ":" scope ":" ns[p[1]] }
        out = out (i > 1 ? "," : "") $i } print out }'
}
callers() { printf '%s' "$1" | tr ',' '\n' | cut -d: -f1 | tr '\n' ' '; }
token_of() { printf '%s' "$SPEC" | tr ',' '\n' | awk -F: -v n="$1" '$1 == n { print $2 }'; }

describe() {
  log "Guard 모드: $GUARD_MODE (네임스페이스 제한도 이 모드를 따른다: ENFORCE 만 거부)"
  for e in $(printf '%s' "$SPEC" | tr ',' '\n'); do
    k="$(printf '%s' "$e" | awk -F: '{print NF}')"
    log "  호출자 $(printf '%s' "$e" | cut -d: -f1): $([ "$k" -ge 4 ] && echo "네임스페이스 $(printf '%s' "$e" | cut -d: -f4)" || echo '제한 없음')"
  done
  log "컨테이너 $GUARD: $(docker inspect "$GUARD" --format '{{.State.Status}}/{{.State.Health.Status}}' 2>/dev/null || echo 없음)"
}

STAMP="$(date +%Y%m%d-%H%M%S)"
ENV_BAK="$ENV_FILE.bak-namespaces-$STAMP"
STARTED=false
rollback() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$STARTED" = true ]; then
    trap - EXIT
    log "!! 실패 (exit $code). .env 를 되돌리고 guard 를 다시 만든다"
    cp -p "$ENV_BAK" "$ENV_FILE" || true
    recreate_guard || true
    exit "$code"
  fi
}
trap rollback EXIT

case "$MODE_ARG" in
  --check) describe; exit 0 ;;
  --rollback)
    LAST="$(ls -1t "$ENV_FILE".bak-namespaces-* 2>/dev/null | head -1 || true)"
    [ -n "$LAST" ] || die "되돌릴 백업(.env.bak-namespaces-*)이 없다"
    log "복원: $LAST"
    cp -p "$LAST" "$ENV_FILE"; recreate_guard
    wait_healthy "$GUARD" || die "guard 가 healthy 로 돌아오지 않았다"
    log "되돌렸다(네임스페이스 제한 없음)."; exit 0 ;;
  --apply) ;;
  *) echo "사용법: bash -s -- --check|--apply|--rollback" >&2; exit 2 ;;
esac

NEW_SPEC="$(build_spec)"
[ "$NEW_SPEC" != "$SPEC" ] || { log "이미 모두 적용돼 있다. 변경하지 않는다."; describe; exit 0; }
[ "$GUARD_MODE" = ENFORCE ] || log "참고: Guard 모드가 $GUARD_MODE 라 위반은 로그만 남기고 통과한다(ENFORCE 에서만 거부)."

cp -p "$ENV_FILE" "$ENV_BAK"; log "백업: $ENV_BAK"
STARTED=true
set_var "$ENV_FILE" DORO_GUARD_SERVICE_TOKENS "$NEW_SPEC"
log "1/3 guard-api 재생성"
recreate_guard
wait_healthy "$GUARD" || die "guard 가 healthy 로 돌아오지 않았다 (네임스페이스를 지원하지 않는 버전이거나 형식 오류)"

log "2/3 검증 (데이터를 쓰지 않는다)"
for name in $(callers "$NEW_SPEC"); do
  tok="$(token_of "$name")"
  [ -n "$tok" ] || die "$name 토큰을 읽지 못했다"
  read_code="$(http -X POST "$GUARD_URL/api/v1/guard/check" -H 'Content-Type: application/json' -H "X-Doro-Service-Token: $tok" -d "$CHECK_BODY")"
  [ "$read_code" = 200 ] || die "$name 토큰으로 조회가 $read_code 이다"
  if printf '%s' "$NEW_SPEC" | tr ',' '\n' | awk -F: -v n="$name" '$1 == n && NF >= 4 { f = 1 } END { exit !f }'; then
    w="$(http -X POST "$GUARD_URL/api/v1/guard/tuples" -H 'Content-Type: application/json' -H "X-Doro-Service-Token: $tok" -d "$PROBE_BODY")"
    log "  $name: 조회 $read_code / system 쓰기 시도 $w"
    if [ "$GUARD_MODE" = ENFORCE ]; then
      [ "$w" = 403 ] || die "$name 이 소유하지 않은 네임스페이스에 쓸 수 있다($w). 통과했다면 system:namespace-probe 튜플이 생겼을 수 있으니 확인하고 지운다"
    fi
  else
    log "  $name: 조회 $read_code / 제한 없음"
  fi
done

log "3/3 컨테이너·서비스 헬스"
for c in doro-auth-api doro-blog-backend; do
  docker inspect "$c" >/dev/null 2>&1 || continue
  wait_healthy "$c" || die "$c 가 healthy 가 아니다"
done
for c in doro-party-api doro-games-api; do
  docker inspect "$c" >/dev/null 2>&1 || continue
  [ "$(docker inspect "$c" --format '{{.State.Status}}')" = running ] || die "$c 가 실행 중이 아니다"
done
STARTED=false
log "완료. 서비스가 평소처럼 동작하는지(글쓰기, 파티 지도, 게임 점수) 한 번씩 써 본다."
log "문제가 생기면 즉시: ssh mini 'bash -s -- --rollback' < scripts/set-guard-namespaces.sh"
