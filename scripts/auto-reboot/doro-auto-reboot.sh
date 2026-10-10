#!/usr/bin/env bash
# 매주 정해진 시각에 systemd 타이머가 실행한다(root). "재부팅 대기(/var/run/reboot-required)가 있을 때만" 안전 확인을 거쳐 재부팅한다.
# 대기가 없으면 아무것도 하지 않는다. 배포·백업·패키지 작업이 진행 중이거나 컨테이너가 이미 비정상이면 그날은 건너뛴다(다음 주에 다시 본다).
# 결과는 syslog(태그 doro-autoreboot)로 남긴다 -> journal -> 중앙 Loki -> 파이의 월요일 보고가 모아서 텔레그램으로 알린다.
#   CHECK host=<이름> result=not-needed | skipped reason=<이유> | rebooting kernel=<버전> pkgs=<패키지>
# 설정은 /etc/doro-auto-reboot.conf(install.sh 가 서버별로 만든다). 이 스크립트는 값을 코드에 박지 않는다.
set -uo pipefail

CONF="${AUTO_REBOOT_CONF:-/etc/doro-auto-reboot.conf}"
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"
REBOOT_REQUIRED_FILE="${REBOOT_REQUIRED_FILE:-/var/run/reboot-required}"
REBOOT_PKGS_FILE="${REBOOT_PKGS_FILE:-${REBOOT_REQUIRED_FILE}.pkgs}"
STATE_DIR="${STATE_DIR:-/var/lib/doro-auto-reboot}"
REBOOT_CMD="${REBOOT_CMD:-systemctl reboot}"
RUNNER_PROCESS_PATTERN="${RUNNER_PROCESS_PATTERN:-bin/Runner[.]Worker}"
BACKUP_LOCK_FILES="${BACKUP_LOCK_FILES:-}"
APT_LOCK_FILES="${APT_LOCK_FILES:-/var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/lib/apt/lists/lock}"
CHECK_DOCKER="${CHECK_DOCKER:-auto}"
LOGGER="${LOGGER:-logger}"
HOST="${AUTO_REBOOT_HOST:-$(hostname)}"

log() { local level="$1"; shift; "$LOGGER" -t doro-autoreboot -p "user.$level" -- "$*" 2>/dev/null || true; printf '[%s] %s\n' "$(date +%T)" "$*"; }

if [ ! -e "$REBOOT_REQUIRED_FILE" ]; then
  log info "CHECK host=$HOST result=not-needed"
  exit 0
fi

# 지금 재부팅하면 안 되는 이유를 모은다.
reasons=()
for f in $APT_LOCK_FILES; do
  [ -e "$f" ] || continue
  if command -v fuser >/dev/null 2>&1 && fuser "$f" >/dev/null 2>&1; then reasons+=("패키지작업중"); break; fi
done
if pgrep -f "$RUNNER_PROCESS_PATTERN" >/dev/null 2>&1; then reasons+=("CI작업진행중"); fi
for f in $BACKUP_LOCK_FILES; do
  [ -e "$f" ] || continue
  if ! flock -n "$f" true 2>/dev/null; then reasons+=("백업진행중"); break; fi
done
use_docker=false
case "$CHECK_DOCKER" in yes) use_docker=true ;; auto) command -v docker >/dev/null 2>&1 && use_docker=true ;; esac
if [ "$use_docker" = true ]; then
  bad="$(docker ps --format '{{.Names}} {{.Status}}' 2>/dev/null | grep -E 'unhealthy|starting' | awk '{print $1}' | tr '\n' ',' | sed 's/,$//')"
  [ -z "$bad" ] || reasons+=("컨테이너비정상:$bad")
fi

if [ "${#reasons[@]}" -gt 0 ]; then
  joined="$(IFS=,; echo "${reasons[*]}")"
  log warning "CHECK host=$HOST result=skipped reason=$joined"
  exit 0
fi

pkgs="$(head -5 "$REBOOT_PKGS_FILE" 2>/dev/null | tr '\n' ',' | sed 's/,$//')"
mkdir -p "$STATE_DIR"
printf 'EPOCH=%s\nKERNEL=%s\n' "$(date +%s)" "$(uname -r)" > "$STATE_DIR/pending"
log notice "CHECK host=$HOST result=rebooting kernel=$(uname -r) pkgs=${pkgs:-?}"
sync
# shellcheck disable=SC2086
exec $REBOOT_CMD
