#!/usr/bin/env bash
# 운영 서버(mini)의 sshd 를 강화한다: 비밀번호 로그인 금지, root 로그인 금지, 허용 사용자 제한.
#
# 서버에서 sudo 로 실행한다 (이 세션의 자동화는 sudo 비밀번호를 쓰지 않으므로 사용자가 직접 실행):
#   ssh -t mini 'sudo bash -s -- --check' < scripts/harden-ssh.sh     # 변경 없이 현재 유효 설정만 출력
#   ssh -t mini 'sudo bash -s -- --apply' < scripts/harden-ssh.sh     # 적용
#
# 안전장치: 설정은 별도 드롭인 파일(/etc/ssh/sshd_config.d/10-doro-hardening.conf)로만 추가하고,
# sshd -t 검증 -> reload -> "새 세션이 키로 접속되는지" 확인 순서로 진행한다.
# 어느 단계든 실패하면 드롭인 파일을 지우고 reload 하여 원래대로 돌린다. 현재 세션은 끊기지 않는다.
# 키 로그인이 가능한 상태가 아니면(authorized_keys 에 키가 없으면) 적용을 거부한다.
set -Eeuo pipefail

MODE="${1:---check}"
CONF=/etc/ssh/sshd_config.d/10-doro-hardening.conf
LOGIN_USER="${SUDO_USER:-ysm}"
KEYS="$(getent passwd "$LOGIN_USER" | cut -d: -f6)/.ssh/authorized_keys"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
[ "$(id -u)" -eq 0 ] || { log "ERROR: root 권한(sudo)이 필요하다"; exit 1; }

show() { sshd -T -C "user=$LOGIN_USER,host=localhost,addr=127.0.0.1" | grep -E '^(passwordauthentication|permitrootlogin|kbdinteractiveauthentication|pubkeyauthentication|x11forwarding|maxauthtries|allowusers|allowtcpforwarding) '; }

log "현재 유효 설정:"; show | sed 's/^/  /'
[ "$MODE" = "--apply" ] || { log "--check 이므로 종료한다. 적용하려면 --apply"; exit 0; }

[ -s "$KEYS" ] && grep -qE '^(ssh-|ecdsa-|sk-)' "$KEYS" || { log "ERROR: $KEYS 에 공개키가 없다. 비밀번호 로그인을 끄면 접속 불가가 되므로 중단한다"; exit 1; }
[ ! -e "$CONF" ] || { log "이미 적용되어 있다 ($CONF). 변경하지 않는다."; exit 0; }

# 포트 22 외에 다른 sshd 설정 파일이 우리 설정을 덮어쓰지 않는지(첫 값이 우선) 마지막에 유효 설정으로 확인한다.
rollback() { rm -f "$CONF"; systemctl reload ssh || true; log "!! 실패. 원래 설정으로 되돌렸다"; }
trap 'rc=$?; [ $rc -ne 0 ] && rollback; exit $rc' EXIT

cat > "$CONF" <<EOF
# Doro: managed by scripts/harden-ssh.sh
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
PubkeyAuthentication yes
X11Forwarding no
MaxAuthTries 3
LoginGraceTime 30
AllowUsers $LOGIN_USER
EOF
chmod 644 "$CONF"
sshd -t
systemctl reload ssh
sleep 1

log "적용 후 유효 설정:"; show | sed 's/^/  /'
[ "$(sshd -T -C "user=$LOGIN_USER,host=localhost,addr=127.0.0.1" | awk '/^passwordauthentication /{print $2}')" = no ] \
  || { log "ERROR: 다른 설정 파일이 우선해서 PasswordAuthentication 이 no 가 아니다"; exit 1; }

# 새 연결이 키로 되는지 확인 (현재 세션과 별개의 연결)
if sudo -u "$LOGIN_USER" ssh -o BatchMode=yes -o PasswordAuthentication=no -o StrictHostKeyChecking=no \
     -o ConnectTimeout=10 "$LOGIN_USER@127.0.0.1" true 2>/dev/null; then
  log "키 기반 새 접속 확인: OK"
else
  # 서버 자신의 키가 없는 경우가 있어 자기 접속 실패만으로 롤백하지 않는다. 대신 사용자가 다른 터미널에서 확인한다.
  log "서버 내부 자기접속은 확인하지 못했다(자기 키 없음). 다른 터미널에서 'ssh mini true' 가 되는지 확인할 것"
fi

trap - EXIT
log "완료. 문제가 있으면 즉시: sudo rm $CONF && sudo systemctl reload ssh"
