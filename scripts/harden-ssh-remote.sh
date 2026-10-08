#!/usr/bin/env bash
# 여러 서버의 SSH 를 한 번에 강화한다(비밀번호 로그인 금지, root 로그인 금지, 허용 사용자 제한). 맥처럼 서버에 키로 접속되는 곳에서 실행한다.
# 서버 안에서 하는 일은 scripts/harden-ssh.sh 가 한다(드롭인 파일 추가 -> sshd -t -> reload -> 실패하면 자동 복구).
#
#   scripts/harden-ssh-remote.sh --check notebook pi          # 변경 없이 현재 유효 설정만 출력
#   scripts/harden-ssh-remote.sh --apply notebook pi          # 적용 (서버마다 sudo 비밀번호를 물어본다)
#   scripts/harden-ssh-remote.sh --apply --passwd notebook    # 적용한 뒤 그 서버의 로그인 비밀번호도 바꾼다(직접 입력)
#
# 안전장치: 적용 전에 맥에서 그 서버로 키 로그인이 되는지 확인하고, 적용 후에도 새 연결이 키로 되는지 확인한다.
# 비밀번호는 이 스크립트가 읽거나 저장하지 않는다. passwd 는 서버의 표준 프롬프트에서 사람이 입력한다.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="--check"; CHANGE_PASSWD=false; HOSTS=()
for arg in "$@"; do
  case "$arg" in
    --check|--apply) MODE="$arg" ;;
    --passwd)        CHANGE_PASSWD=true ;;
    -*)              echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
    *)               HOSTS+=("$arg") ;;
  esac
done
[ "${#HOSTS[@]}" -gt 0 ] || { echo "사용법: $0 [--check|--apply] [--passwd] <호스트>..." >&2; exit 2; }
[ "$CHANGE_PASSWD" = false ] || [ "$MODE" = "--apply" ] || { echo "--passwd 는 --apply 와 함께 쓴다" >&2; exit 2; }

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
key_login_ok() { ssh -o BatchMode=yes -o PasswordAuthentication=no -o ConnectTimeout=10 "$1" true 2>/dev/null; }

FAILED=()
for host in "${HOSTS[@]}"; do
  log "== $host =="
  if ! key_login_ok "$host"; then
    log "ERROR: $host 에 키로 접속되지 않는다. 비밀번호 로그인을 끄면 못 들어가므로 건너뛴다."; FAILED+=("$host"); continue
  fi
  # 서버에서 sudo 로 harden-ssh.sh 를 실행한다. 스크립트를 표준입력으로 흘리면 ssh 가 터미널(-t)을 만들지 않아
  # sudo 가 비밀번호를 물을 수 없으므로, 홈 폴더에 임시 파일로 올려 실행하고 끝나면 지운다(/tmp 는 쓰지 않는다: 바꿔치기 방지).
  remote_script=".harden-ssh-$$.sh"
  scp -q -o BatchMode=yes "$HERE/harden-ssh.sh" "$host:$remote_script" || { log "ERROR: $host 로 스크립트를 올리지 못했다"; FAILED+=("$host"); continue; }
  if ssh -t "$host" "sudo bash ~/$remote_script $MODE; rc=\$?; rm -f ~/$remote_script; exit \$rc"; then
    if [ "$MODE" = "--apply" ]; then
      key_login_ok "$host" && log "  적용 후 키 로그인 확인: OK" || { log "ERROR: 적용 후 키 로그인이 안 된다. 서버에서 즉시: sudo rm /etc/ssh/sshd_config.d/10-doro-hardening.conf && sudo systemctl reload ssh"; FAILED+=("$host"); continue; }
      if ssh -o BatchMode=yes -o PreferredAuthentications=password -o PubkeyAuthentication=no -o ConnectTimeout=10 "$host" true 2>&1 | grep -q "Permission denied"; then
        log "  비밀번호 로그인 거부 확인: OK"
      else
        log "  경고: 비밀번호 로그인이 거부되는지 확인하지 못했다. 직접 점검할 것"
      fi
      if [ "$CHANGE_PASSWD" = true ]; then
        log "  $host 로그인(=sudo) 비밀번호를 바꾼다. 아래 프롬프트에 직접 입력한다."
        ssh -t "$host" 'passwd' || { log "  비밀번호 변경 실패"; FAILED+=("$host:passwd"); }
      fi
    fi
  else
    log "ERROR: $host 적용 실패"; FAILED+=("$host")
  fi
done

echo
if [ "${#FAILED[@]}" -gt 0 ]; then log "실패/건너뜀: ${FAILED[*]}"; exit 1; fi
log "모든 서버 처리 완료 ($MODE)"
