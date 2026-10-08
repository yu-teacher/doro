#!/usr/bin/env bash
# 여러 서버의 sudo 인증 유지 시간을 바꾼다. 맥처럼 서버에 키로 접속되는 곳에서 실행한다(서버마다 sudo 비밀번호를 한 번 묻는다).
#
#   scripts/set-sudo-timeout.sh --check notebook pi mini        # 현재 설정만 출력
#   scripts/set-sudo-timeout.sh 480 notebook pi mini            # 480분(8시간)으로 설정
#   scripts/set-sudo-timeout.sh --reset notebook pi mini        # 기본값(15분)으로 되돌림(드롭인 파일 삭제)
#
# 안전장치
#  - /etc/sudoers.d/ 에 별도 드롭인 파일(90-doro-sudo-timeout)만 쓴다. 기존 sudoers 는 건드리지 않는다.
#  - 임시 파일에 먼저 쓰고 `visudo -cf` 로 문법을 검증한 뒤에만 설치한다(문법 오류가 sudo 를 망가뜨리지 못하게).
#  - tty_tickets(터미널별 인증)는 끄지 않는다: 인증은 sudo 를 친 그 터미널에서만 유효하고, 다른 터미널·백그라운드 프로세스(CI 러너 등)는
#    그 인증을 재사용하지 못한다. 새 터미널 탭이나 새 SSH 접속에서는 다시 비밀번호를 한 번 친다.
set -Eeuo pipefail

MODE="set"; MINUTES=""; HOSTS=()
for arg in "$@"; do
  case "$arg" in
    --check) MODE="check" ;;
    --reset) MODE="reset" ;;
    -*)      echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
    [0-9]*)  MINUTES="$arg" ;;
    *)       HOSTS+=("$arg") ;;
  esac
done
[ "${#HOSTS[@]}" -gt 0 ] || { echo "사용법: $0 [--check|--reset|<분>] <호스트>..." >&2; exit 2; }
if [ "$MODE" = "set" ]; then
  [[ "$MINUTES" =~ ^[0-9]+$ ]] && [ "$MINUTES" -ge 1 ] && [ "$MINUTES" -le 1440 ] || { echo "분은 1~1440 사이 숫자여야 한다 (예: 480)" >&2; exit 2; }
fi

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# 서버에서 sudo 로 실행할 내용. 중첩 heredoc 을 명령 치환 안에 넣으면 맥의 bash 3.2 가 해석하지 못해 함수로 둔다.
write_remote_script() {
  cat <<'REMOTE_EOF'
set -Eeuo pipefail
mode="$1"; minutes="${2:-}"
target=/etc/sudoers.d/90-doro-sudo-timeout
if [ "$mode" = reset ]; then
  rm -f "$target"
  echo "기본값으로 되돌렸다(드롭인 파일 삭제)"
elif [ "$mode" = set ]; then
  tmp="$(mktemp)"
  printf '# Doro: sudo 인증 유지 시간(분). scripts/set-sudo-timeout.sh 가 관리한다.\nDefaults timestamp_timeout=%s\n' "$minutes" > "$tmp"
  if ! visudo -cf "$tmp" >/dev/null; then rm -f "$tmp"; echo "ERROR: sudoers 문법 검증 실패. 설치하지 않았다" >&2; exit 1; fi
  install -o root -g root -m 0440 "$tmp" "$target"
  rm -f "$tmp"
  if ! visudo -c >/dev/null; then rm -f "$target"; echo "ERROR: 설치 후 전체 sudoers 검증 실패. 되돌렸다" >&2; exit 1; fi
  echo "설정함: ${minutes}분"
fi
sudo -V | grep -iE "Authentication timestamp timeout|Type of authentication timestamp record" || true
echo "sudoers.d: $(ls /etc/sudoers.d/ | tr '\n' ' ')"
REMOTE_EOF
}

FAILED=()
for host in "${HOSTS[@]}"; do
  log "== $host =="
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" true 2>/dev/null || { log "ERROR: $host 에 키로 접속되지 않는다"; FAILED+=("$host"); continue; }
  remote_script=".sudo-timeout-$$.sh"
  tmp_local="$(mktemp)"
  write_remote_script > "$tmp_local"
  scp -q -o BatchMode=yes "$tmp_local" "$host:$remote_script" || { rm -f "$tmp_local"; log "ERROR: $host 로 스크립트를 올리지 못했다"; FAILED+=("$host"); continue; }
  rm -f "$tmp_local"
  if ssh -t "$host" "sudo bash ~/$remote_script $MODE $MINUTES; rc=\$?; rm -f ~/$remote_script; exit \$rc"; then
    log "  $host 완료"
  else
    log "ERROR: $host 실패"; FAILED+=("$host")
  fi
done
echo
if [ "${#FAILED[@]}" -gt 0 ]; then log "실패/건너뜀: ${FAILED[*]}"; exit 1; fi
log "모든 서버 처리 완료"
