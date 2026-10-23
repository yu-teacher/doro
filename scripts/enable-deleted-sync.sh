#!/usr/bin/env bash
# 파티·게임·면접·알림의 탈퇴 동기화(Doro 에서 영구 탈퇴한 사용자의 서비스 데이터를 정리)를 켜기 위한 서버 준비. 운영 서버(mini)에서 실행한다.
# 비밀 값(서비스 토큰)은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --check'                < scripts/enable-deleted-sync.sh   # 읽기 전용: 준비 상태
#   ssh mini 'bash -s -- --apply'                < scripts/enable-deleted-sync.sh   # 파일만 고친다(컨테이너는 안 건드림)
#   ssh mini 'bash -s -- --apply --restart-auth' < scripts/enable-deleted-sync.sh   # 위 + auth 컨테이너만 다시 만든다(토큰을 읽게 하려면 필요, 약 25초)
#
# --apply 가 하는 일 (이미 있으면 건드리지 않는다)
#   1) 서비스별 토큰 생성: party, games 각각 openssl rand -hex 32 (IAM 은 32자 이상을 요구한다)
#   2) ~/doro/.env 의 DORO_IAM_INTERNAL_TOKENS 에 'party:<토큰>', 'games:<토큰>' 항목 추가(기존 항목 유지)
#   3) 각 서비스의 서버 쪽 .env(~/doro-party/.env, ~/doro-games/.env)에 DORO_IAM_INTERNAL_TOKEN=<같은 토큰>
#   4) 각 서비스의 서버 쪽 docker-compose.prod.yml 이 그 변수를 컨테이너에 전달하도록 한 줄 추가(백업 후)
# 이 파일들을 고쳐도 실행 중인 컨테이너는 그대로다. 서비스는 코드 배포(PR 머지)로 다시 만들어질 때 새 값을 읽고,
# auth 는 --restart-auth 또는 다음 Doro 배포 때 읽는다. 순서: 이 스크립트 → (필요하면 auth 재시작) → 서비스 PR 머지.
set -Eeuo pipefail

APPLY=false; RESTART_AUTH=false
for a in "$@"; do
  case "$a" in
    --check) ;;
    --apply) APPLY=true ;;
    --restart-auth) RESTART_AUTH=true ;;
    *) echo "사용법: bash -s -- --check | --apply [--restart-auth]" >&2; exit 2 ;;
  esac
done
DORO_DIR="${DORO_DIR:-$HOME/doro}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
ENV_FILE="$DORO_DIR/.env"
AUTH=doro-auth-api
SERVICES=(party games interview notify)

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
has_entry() { get_var "$ENV_FILE" DORO_IAM_INTERNAL_TOKENS | tr ',' '\n' | grep -q "^$1:"; }
entry_token() { get_var "$ENV_FILE" DORO_IAM_INTERNAL_TOKENS | tr ',' '\n' | grep -m1 "^$1:" | cut -d: -f2- || true; }
svc_dir() { printf '%s' "$HOME/doro-$1"; }
svc_var_key() { printf '%s_SESSION_KEY' "$(printf '%s' "$1" | tr a-z A-Z)"; }
compose_passes() { grep -q "DORO_IAM_INTERNAL_TOKEN" "$(svc_dir "$1")/docker-compose.prod.yml"; }
svc_env_token() { get_var "$(svc_dir "$1")/.env" DORO_IAM_INTERNAL_TOKEN; }
healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 60); do healthy "$1" && return 0; sleep 3; done; return 1; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없다"
for s in "${SERVICES[@]}"; do [ -f "$(svc_dir "$s")/.env" ] && [ -f "$(svc_dir "$s")/docker-compose.prod.yml" ] || die "$(svc_dir "$s") 의 .env 또는 docker-compose.prod.yml 이 없다"; done

describe() {
  local s t
  for s in "${SERVICES[@]}"; do
    t="$(svc_env_token "$s")"
    log "[$s] IAM 토큰 항목('$s:'): $(has_entry "$s" && echo 있음 || echo 없음) | 서비스 .env 토큰: $([ -n "$t" ] && echo "있음(${#t}자)" || echo 없음) | compose 전달: $(compose_passes "$s" && echo 예 || echo 아니오)"
    if has_entry "$s" && [ -n "$t" ]; then
      [ "$(entry_token "$s")" = "$t" ] && log "[$s] 두 토큰이 같다" || log "[$s] 경고: IAM 쪽과 서비스 쪽 토큰이 다르다"
    fi
  done
  log "컨테이너 $AUTH: $(docker inspect "$AUTH" --format '{{.State.Status}}/{{.State.Health.Status}} (시작 {{.State.StartedAt}})' 2>/dev/null || echo 없음)"
}

if [ "$APPLY" = false ]; then describe; exit 0; fi

TS="$(date +%Y%m%d-%H%M%S)"
cp -p "$ENV_FILE" "$ENV_FILE.bak-delsync-$TS"; log ".env 백업: $ENV_FILE.bak-delsync-$TS"
for s in "${SERVICES[@]}"; do
  D="$(svc_dir "$s")"
  # 1) 토큰: IAM 쪽 항목이 있으면 그 값, 서비스 쪽이 있으면 그 값, 둘 다 없으면 새로 만든다
  TOKEN="$(entry_token "$s")"
  [ -n "$TOKEN" ] || TOKEN="$(svc_env_token "$s")"
  [ -n "$TOKEN" ] || TOKEN="$(openssl rand -hex 32)"
  [ "${#TOKEN}" -ge 32 ] || die "[$s] 토큰이 32자 미만이다"
  # 2) IAM 쪽 항목
  if has_entry "$s"; then log "[$s] IAM 토큰 항목은 이미 있다(그대로 둔다)"
  else
    CUR="$(get_var "$ENV_FILE" DORO_IAM_INTERNAL_TOKENS)"
    set_var "$ENV_FILE" DORO_IAM_INTERNAL_TOKENS "${CUR:+$CUR,}$s:$TOKEN"; log "[$s] IAM 토큰 항목을 추가했다"
  fi
  # 3) 서비스 쪽 .env
  if [ -n "$(svc_env_token "$s")" ]; then log "[$s] 서비스 .env 토큰은 이미 있다(그대로 둔다)"
  else
    cp -p "$D/.env" "$D/.env.bak-delsync-$TS"
    set_var "$D/.env" DORO_IAM_INTERNAL_TOKEN "$TOKEN"; log "[$s] 서비스 .env 에 토큰을 넣었다(백업: $D/.env.bak-delsync-$TS)"
  fi
  unset TOKEN CUR
  # 4) compose 전달 줄: 세션 키 줄 바로 앞에 넣는다(들여쓰기는 그 줄을 따른다)
  if compose_passes "$s"; then log "[$s] compose 가 이미 토큰을 전달한다"
  else
    C="$D/docker-compose.prod.yml"; cp -p "$C" "$C.bak-delsync-$TS"
    awk -v key="$(svc_var_key "$s")" '
      $0 ~ "^[ ]+" key ":" && !done { match($0, /^[ ]+/); ind=substr($0, 1, RLENGTH); print ind "DORO_IAM_INTERNAL_TOKEN: ${DORO_IAM_INTERNAL_TOKEN:-}"; done=1 }
      { print }' "$C.bak-delsync-$TS" > "$C"
    if compose_passes "$s"; then log "[$s] compose 에 토큰 전달 줄을 추가했다(백업: $C.bak-delsync-$TS)"
    else cp -p "$C.bak-delsync-$TS" "$C"; die "[$s] compose 에 $(svc_var_key "$s") 줄이 없어 전달 줄을 넣지 못했다(되돌렸다)"; fi
  fi
done

if [ "$RESTART_AUTH" = true ]; then
  log "auth 컨테이너를 다시 만든다(약 25초, 그동안 로그인·API 가 잠깐 끊긴다)"
  ( cd "$RUNNER_DIR" && cp "$ENV_FILE" .env && chmod 600 .env && docker compose up -d --no-build --no-deps auth-api ) >/dev/null
  wait_healthy "$AUTH" || die "auth 가 healthy 로 돌아오지 않았다(.env 백업: $ENV_FILE.bak-delsync-$TS)"
  log "auth 가 healthy 로 돌아왔다"
else
  log "auth 는 건드리지 않았다. 새 토큰은 다음 Doro 배포 또는 --restart-auth 로 반영된다."
fi
describe
