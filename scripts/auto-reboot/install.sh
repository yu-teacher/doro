#!/usr/bin/env bash
# 서버에서 root 로 실행한다: 자동 재부팅(매주, 재부팅 대기가 있을 때만)을 설치한다. 이 서버에서 한 번만 하면 된다.
#   sudo bash install.sh --profile mini|notebook|pi --on-calendar "Mon *-*-* 05:10:00" [--user ysm] [--uninstall]
# 설치 위치(전부 root 소유, 일반 사용자가 못 고친다): /usr/local/sbin/doro-auto-reboot{,-after}, /etc/doro-auto-reboot.conf,
#   /etc/systemd/system/doro-auto-reboot.{service,timer}, doro-auto-reboot-after.service
set -Eeuo pipefail

PROFILE=""; ON_CALENDAR=""; RUN_USER="${SUDO_USER:-}"; UNINSTALL=false
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="${2:-}"; shift 2 ;;
    --on-calendar) ON_CALENDAR="${2:-}"; shift 2 ;;
    --user) RUN_USER="${2:-}"; shift 2 ;;
    --uninstall) UNINSTALL=true; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done
[ "$(id -u)" = 0 ] || { echo "root 로 실행한다(sudo bash install.sh ...)" >&2; exit 2; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNIT_DIR=/etc/systemd/system

if [ "$UNINSTALL" = true ]; then
  systemctl disable --now doro-auto-reboot.timer 2>/dev/null || true
  systemctl disable doro-auto-reboot-after.service 2>/dev/null || true
  rm -f "$UNIT_DIR"/doro-auto-reboot.{service,timer} "$UNIT_DIR"/doro-auto-reboot-after.service /usr/local/sbin/doro-auto-reboot /usr/local/sbin/doro-auto-reboot-after /etc/doro-auto-reboot.conf
  systemctl daemon-reload
  echo "제거했다."; exit 0
fi

case "$PROFILE" in mini|notebook|pi) ;; *) echo "--profile 은 mini, notebook, pi 중 하나" >&2; exit 2 ;; esac
[[ "$ON_CALENDAR" =~ ^[A-Za-z0-9\ :*,.-]+$ ]] || { echo "--on-calendar 값이 이상하다(예: \"Mon *-*-* 05:10:00\")" >&2; exit 2; }
[[ "$RUN_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo "--user 가 필요하다(일반 사용자 이름)" >&2; exit 2; }
HOME_DIR="$(getent passwd "$RUN_USER" | cut -d: -f6)"; [ -d "$HOME_DIR" ] || { echo "사용자 $RUN_USER 의 홈을 찾지 못했다" >&2; exit 2; }
systemd-analyze calendar "$ON_CALENDAR" >/dev/null || { echo "OnCalendar 형식이 올바르지 않다" >&2; exit 2; }

install -o root -g root -m 755 "$HERE/doro-auto-reboot.sh" /usr/local/sbin/doro-auto-reboot
install -o root -g root -m 755 "$HERE/doro-auto-reboot-after.sh" /usr/local/sbin/doro-auto-reboot-after

case "$PROFILE" in
  mini)
    cat > /etc/doro-auto-reboot.conf <<CONF
# 미니: 운영 서버. 러너 작업·백업·컨테이너 상태를 확인하고, 부팅 후 게이트웨이 경유 주소로 점검한다.
AUTO_REBOOT_HOST=mini
CHECK_DOCKER=yes
BACKUP_LOCK_FILES="$HOME_DIR/backups/auto/.lock"
AFTER_HTTP_CHECKS="https://127.0.0.1/|200 https://127.0.0.1/login|200 https://127.0.0.1/blog/|200 https://127.0.0.1/menu/|200 https://127.0.0.1/party/|200 https://127.0.0.1/games/|200 https://127.0.0.1/loki/api/v1/labels|401"
AFTER_TIMEOUT_SEC=420
CONF
    ;;
  notebook)
    cat > /etc/doro-auto-reboot.conf <<CONF
# 노트북: 중앙 Loki·Grafana·백업 보관소·CI 러너.
AUTO_REBOOT_HOST=notebook
CHECK_DOCKER=yes
AFTER_HTTP_CHECKS="http://127.0.0.1:3101/ready|200 http://127.0.0.1:3300/api/health|200"
AFTER_TIMEOUT_SEC=300
CONF
    ;;
  pi)
    cat > /etc/doro-auto-reboot.conf <<CONF
# 파이: 외부 감시(워치독)와 백업 사본. 도커를 쓰지 않는다.
AUTO_REBOOT_HOST=pi
CHECK_DOCKER=no
AFTER_TIMEOUT_SEC=180
CONF
    ;;
esac
chmod 644 /etc/doro-auto-reboot.conf

cat > "$UNIT_DIR/doro-auto-reboot.service" <<'UNIT'
[Unit]
Description=Doro auto reboot (only when a reboot is pending)
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/doro-auto-reboot
UNIT
cat > "$UNIT_DIR/doro-auto-reboot.timer" <<UNIT
[Unit]
Description=Doro auto reboot check ($PROFILE)
[Timer]
OnCalendar=$ON_CALENDAR
Persistent=false
[Install]
WantedBy=timers.target
UNIT
cat > "$UNIT_DIR/doro-auto-reboot-after.service" <<'UNIT'
[Unit]
Description=Doro post-reboot health check (only after an auto reboot)
After=network-online.target docker.service
Wants=network-online.target
[Service]
Type=oneshot
TimeoutStartSec=600
ExecStart=/usr/local/sbin/doro-auto-reboot-after
[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now doro-auto-reboot.timer
systemctl enable doro-auto-reboot-after.service
echo "설치 완료($PROFILE). 다음 실행:"; systemctl list-timers doro-auto-reboot.timer --no-pager | sed -n 1,3p
