#!/usr/bin/env bash
# mini 의 백업을 다른 서버(기본 pi)로 오프사이트 복사하도록 설정한다. 두 서버에 모두 ssh 로 접속할 수 있는 곳(예: Mac)에서 실행한다.
# 대상은 TARGET_HOST 로 고른다(~/.ssh/config 의 호스트 이름). 대상마다 전용 키와 연결 별칭이 따로 생기고 backup.env 의 OFFSITE_TARGETS 에 추가된다.
#
#   scripts/setup-offsite-backup.sh                          # pi 로 설정 (여러 번 실행해도 안전)
#   TARGET_HOST=notebook scripts/setup-offsite-backup.sh     # 두 번째 보관소(notebook) 추가
#   scripts/setup-offsite-backup.sh --check                  # 아무것도 바꾸지 않고 현재 상태만 점검
#
# 하는 일
#   1) mini 에 이 용도 전용 SSH 키를 만든다 (다른 곳에서는 쓰지 않는다)
#   2) 대상 서버의 authorized_keys 에 그 키를 "rrsync 로 ~/doro-backups 안에서만" 허용하도록 제한해 등록한다
#      (셸 접속, 포트 포워딩, 다른 경로 접근 불가)
#   3) mini 가 대상 서버의 호스트 키를 신뢰하도록 등록하고, 연결 별칭(pi-backup)과 ~/ops/backup.env 를 만든다
#   4) 대상 서버에 보관 정책(일 30 / 주 12 / 월 12)을 정리하는 cron 을 등록한다
#      (오프사이트 복사는 삭제를 전파하지 않으므로 오래된 세대는 대상 서버가 스스로 정리한다)
#   5) 제한이 실제로 걸렸는지, 복사가 되는지 검증한다
set -Eeuo pipefail

MINI="${MINI_HOST:-mini}"
TARGET="${TARGET_HOST:-${PI_HOST:-pi}}"
KEY_NAME="${TARGET}_backup_ed25519"      # pi 는 기존 이름(pi_backup_ed25519)과 같다
ALIAS="${TARGET}-backup"
REMOTE_DIR="doro-backups"          # 대상 서버의 홈 아래 디렉터리 (rrsync 의 루트)
CHECK_ONLY=false
[ "${1:-}" = "--check" ] && CHECK_ONLY=true

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10)
on_mini() { ssh "${SSH_OPTS[@]}" "$MINI" "$@"; }
on_target()   { ssh "${SSH_OPTS[@]}" "$TARGET" "$@"; }
log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { log "ERROR: $*"; exit 1; }

# ------------------------------------------------------------ 사전 점검 (읽기 전용)
log "== 사전 점검 =="
on_mini true || die "mini($MINI)에 ssh 로 접속할 수 없다"
on_target true   || die "대상($TARGET)에 ssh 로 접속할 수 없다"
T_ADDR="$(ssh -G "$TARGET" | awk '/^hostname /{print $2}')"
T_USER="$(ssh -G "$TARGET" | awk '/^user /{print $2}')"
T_HOME="$(on_target 'printf %s "$HOME"')"
[ -n "$T_ADDR" ] && [ -n "$T_USER" ] && [ -n "$T_HOME" ] || die "대상 서버 접속 정보를 읽지 못했다"
on_target 'test -x /usr/bin/rrsync && command -v rsync >/dev/null' || die "대상 서버에 rsync/rrsync 가 없다 (sudo apt install rsync)"
on_mini 'command -v rsync >/dev/null' || die "mini 에 rsync 가 없다"
[ "$(on_target "df --output=avail -BM '$T_HOME' | tail -1 | tr -dc 0-9")" -gt 5000 ] || die "대상 서버의 여유 공간이 5GB 미만이다"
log "  대상: $T_USER@$T_ADDR ($T_HOME), rsync/rrsync 있음, 공간 충분"

HAVE_KEY="$(on_mini "test -f ~/.ssh/$KEY_NAME && echo yes || echo no")"
log "  mini 전용 키: $([ "$HAVE_KEY" = yes ] && echo '있음' || echo '없음')"
if [ "$CHECK_ONLY" = true ]; then
  if [ "$HAVE_KEY" = yes ]; then
    PUB="$(on_mini "cat ~/.ssh/$KEY_NAME.pub")"; BLOB="$(echo "$PUB" | awk '{print $2}')"
    log "  pi authorized_keys 에 등록됨: $(on_target "grep -c '$BLOB' ~/.ssh/authorized_keys || true")"
    log "  mini 연결 별칭: $(on_mini "grep -c '^Host $ALIAS\$' ~/.ssh/config 2>/dev/null || true")"
    log "  mini backup.env: $(on_mini "cat ~/ops/backup.env 2>/dev/null | tr '\n' ' '")"
  fi
  exit 0
fi

# ------------------------------------------------------------ 1) mini 전용 키
if [ "$HAVE_KEY" != yes ]; then
  on_mini "mkdir -p ~/.ssh && chmod 700 ~/.ssh && ssh-keygen -t ed25519 -N '' -C 'mini-backup->$TARGET' -f ~/.ssh/$KEY_NAME >/dev/null && chmod 600 ~/.ssh/$KEY_NAME"
  log "1) mini 전용 키 생성: ~/.ssh/$KEY_NAME"
else
  log "1) mini 전용 키 재사용"
fi
PUBKEY="$(on_mini "cat ~/.ssh/$KEY_NAME.pub")"
BLOB="$(echo "$PUBKEY" | awk '{print $2}')"
[ -n "$BLOB" ] || die "공개키를 읽지 못했다"

# ------------------------------------------------------------ 2) pi: 제한된 키 등록
on_target "mkdir -p '$T_HOME/$REMOTE_DIR' && chmod 700 '$T_HOME/$REMOTE_DIR' && mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
if on_target "grep -q '$BLOB' ~/.ssh/authorized_keys"; then
  log "2) 대상 서버에 이미 등록됨"
else
  ENTRY="restrict,command=\"/usr/bin/rrsync $T_HOME/$REMOTE_DIR\" $PUBKEY"
  on_target "cp -p ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak-offsite-\$(date +%Y%m%d%H%M%S) && printf '%s\n' '$ENTRY' >> ~/.ssh/authorized_keys"
  log "2) 대상 서버에 제한된 키 등록 (rrsync $T_HOME/$REMOTE_DIR 안에서만, restrict)"
fi

# ------------------------------------------------------------ 3) mini: 호스트 키 신뢰, 별칭, 설정
# 대상 서버의 호스트 키는 이미 신뢰하는 연결(이 스크립트의 대상 서버 접속)로 읽는다. 처음 보는 키를 그냥 받아들이지 않는다.
T_HOSTKEY="$(on_target 'cat /etc/ssh/ssh_host_ed25519_key.pub' | awk '{print $1" "$2}')"
[ -n "$T_HOSTKEY" ] || die "대상 서버 호스트 키를 읽지 못했다"
on_mini "mkdir -p ~/.ssh && touch ~/.ssh/known_hosts && (grep -q '^$T_ADDR ' ~/.ssh/known_hosts || echo '$T_ADDR $T_HOSTKEY' >> ~/.ssh/known_hosts)"
if on_mini "grep -q '^Host $ALIAS\$' ~/.ssh/config 2>/dev/null"; then
  log "3) mini 연결 별칭 이미 있음"
else
  on_mini "touch ~/.ssh/config && chmod 600 ~/.ssh/config && printf '\nHost $ALIAS\n    HostName $T_ADDR\n    User $T_USER\n    IdentityFile ~/.ssh/$KEY_NAME\n    IdentitiesOnly yes\n    StrictHostKeyChecking yes\n' >> ~/.ssh/config"
  log "3) mini 연결 별칭($ALIAS) 추가"
fi
on_mini bash -s -- "$ALIAS" <<'REMOTE'
set -Eeuo pipefail
alias="$1"; env_file="$HOME/ops/backup.env"
mkdir -p "$HOME/ops"; touch "$env_file"; chmod 600 "$env_file"
if ! grep -q '^OFFSITE_TARGETS=' "$env_file"; then
  # 예전 한 줄짜리 OFFSITE_TARGET 설정을 목록으로 옮긴다
  old="$(grep '^OFFSITE_TARGET=' "$env_file" | head -1 | cut -d= -f2- || true)"
  sed -i '/^OFFSITE_TARGET=/d' "$env_file"
  printf 'OFFSITE_TARGETS="%s"\n' "${old:+$old }$alias:./" >> "$env_file"
elif ! grep '^OFFSITE_TARGETS=' "$env_file" | grep -q "$alias:"; then
  sed -i "s#^OFFSITE_TARGETS=\"\(.*\)\"#OFFSITE_TARGETS=\"\1 $alias:./\"#" "$env_file"
fi
REMOTE
log "   mini ~/ops/backup.env: $(on_mini 'cat ~/ops/backup.env' | tr '\n' ' ')"

# 최신 backup.sh 설치 (backup.env 와 오프사이트 복사를 지원하는 버전)
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
on_mini 'cp -p ~/ops/backup.sh ~/ops/backup.sh.prev 2>/dev/null || true; cat > ~/ops/backup.sh.new && chmod 700 ~/ops/backup.sh.new && mv ~/ops/backup.sh.new ~/ops/backup.sh' < "$HERE/backup.sh"
log "   mini ~/ops/backup.sh 갱신 (이전 버전: backup.sh.prev)"

# ------------------------------------------------------------ 4) pi: 보관 정책 cron
PRUNE='#!/usr/bin/env bash
# mini 에서 받은 백업 세대를 정리한다 (일 30 / 주 12 / 월 12). 복사는 삭제를 전파하지 않으므로 여기서만 지운다.
set -euo pipefail
ROOT="$HOME/doro-backups"
for tier in daily:30 weekly:12 monthly:12; do
  dir="$ROOT/${tier%%:*}"; keep="${tier##*:}"
  [ -d "$dir" ] || continue
  ls -1d "$dir"/[0-9]* 2>/dev/null | sort | head -n "-$keep" | xargs -r rm -rf
done
'
printf '%s' "$PRUNE" | on_target 'mkdir -p ~/ops && cat > ~/ops/prune-doro-backups.sh && chmod 700 ~/ops/prune-doro-backups.sh'
on_target 'crontab -l 2>/dev/null | grep -q prune-doro-backups || { (crontab -l 2>/dev/null; echo "30 6 * * * \$HOME/ops/prune-doro-backups.sh >> \$HOME/doro-backups/prune.log 2>&1") | crontab -; }'
log "4) 대상 서버 보관 정책 cron 등록 (매일 06:30): $(on_target 'crontab -l | grep -c prune-doro-backups') 개"

# ------------------------------------------------------------ 5) 검증
log "== 검증 =="
# a) 제한된 키로는 셸이 열리지 않는다
SHELL_TEST="$(on_mini "ssh -o BatchMode=yes -o ConnectTimeout=10 $ALIAS 'echo SHELL_OPENED' 2>&1 || true")"
if echo "$SHELL_TEST" | grep -q SHELL_OPENED; then die "제한이 걸리지 않았다: 키로 셸 명령이 실행된다"; fi
log "  셸 명령 실행 차단됨 (rrsync 강제)"
# b) 루트 밖으로는 쓸 수 없다
OUTSIDE="$(on_mini "echo probe > /tmp/offsite-probe.txt; rsync -a -e 'ssh -o BatchMode=yes' /tmp/offsite-probe.txt $ALIAS:../offsite-probe-outside.txt 2>&1; echo exit=\$?; rm -f /tmp/offsite-probe.txt")"
if on_target "test -e '$T_HOME/offsite-probe-outside.txt'"; then on_target "rm -f '$T_HOME/offsite-probe-outside.txt'"; die "루트 밖(../)에 파일이 써졌다"; fi
log "  루트 밖 경로 쓰기 차단됨"
# c) 실제 복사가 된다
on_mini '~/ops/backup.sh backup' | tail -2 | sed 's/^/    /'
LATEST="$(on_mini 'ls -1d ~/backups/auto/daily/[0-9]* | sort | tail -1 | xargs basename')"
on_target "test -d '$T_HOME/$REMOTE_DIR/daily/$LATEST'" || die "대상 서버에 최신 백업($LATEST)이 도착하지 않았다"
on_target "cd '$T_HOME/$REMOTE_DIR/daily/$LATEST' && sha256sum -c SHA256SUMS --quiet" || die "대상 서버 사본의 체크섬이 맞지 않는다"
log "  대상 서버에 최신 백업 도착 + 체크섬 일치: $LATEST"
log "  대상 서버 사본 용량: $(on_target "du -sh '$T_HOME/$REMOTE_DIR' | cut -f1")"
on_mini '~/ops/backup.sh status' | sed 's/^/    /' || true
log "완료"
