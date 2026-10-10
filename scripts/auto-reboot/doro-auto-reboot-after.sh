#!/usr/bin/env bash
# 부팅이 끝난 뒤 systemd(oneshot)가 실행한다. 자동 재부팅이 남긴 표시(pending)가 있을 때만 동작한다.
# 컨테이너가 모두 정상이 되고 설정한 주소가 기대한 응답을 줄 때까지 기다린 뒤 결과를 syslog 로 남긴다.
#   BOOT-OK host=<이름> kernel=<새 커널> took=<초> | BOOT-FAIL host=<이름> reason=<이유> took=<초>
set -uo pipefail

CONF="${AUTO_REBOOT_CONF:-/etc/doro-auto-reboot.conf}"
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"
STATE_DIR="${STATE_DIR:-/var/lib/doro-auto-reboot}"
CHECK_DOCKER="${CHECK_DOCKER:-auto}"
AFTER_HTTP_CHECKS="${AFTER_HTTP_CHECKS:-}"     # 공백으로 구분한 "<주소>|<기대 코드>" 목록
AFTER_TIMEOUT_SEC="${AFTER_TIMEOUT_SEC:-420}"
AFTER_INTERVAL_SEC="${AFTER_INTERVAL_SEC:-5}"
LOGGER="${LOGGER:-logger}"
HOST="${AUTO_REBOOT_HOST:-$(hostname)}"
PENDING="$STATE_DIR/pending"

log() { local level="$1"; shift; "$LOGGER" -t doro-autoreboot -p "user.$level" -- "$*" 2>/dev/null || true; printf '[%s] %s\n' "$(date +%T)" "$*"; }

[ -f "$PENDING" ] || exit 0
EPOCH=""; KERNEL=""
# shellcheck disable=SC1090
. "$PENDING"
use_docker=false
case "$CHECK_DOCKER" in yes) use_docker=true ;; auto) command -v docker >/dev/null 2>&1 && use_docker=true ;; esac

problems() {
  local out=() item url want code n
  if [ "$use_docker" = true ]; then
    n="$(docker ps -q 2>/dev/null | wc -l | tr -d ' ')"
    [ "${n:-0}" -gt 0 ] || out+=("컨테이너없음")
    n="$(docker ps --format '{{.Names}} {{.Status}}' 2>/dev/null | grep -cE 'unhealthy|starting' || true)"
    [ "${n:-0}" = 0 ] || out+=("컨테이너기동중또는비정상:${n}")
  fi
  for item in $AFTER_HTTP_CHECKS; do
    url="${item%|*}"; want="${item##*|}"
    code="$(curl -sk -o /dev/null -m 6 -w '%{http_code}' "$url" 2>/dev/null || true)"
    [ "$code" = "$want" ] || out+=("${url##*://}:${code:-없음}(기대${want})")
  done
  (IFS=';'; echo "${out[*]:-}")
}

deadline=$((SECONDS + AFTER_TIMEOUT_SEC))
res="$(problems)"
while [ -n "$res" ] && [ "$SECONDS" -lt "$deadline" ]; do sleep "$AFTER_INTERVAL_SEC"; res="$(problems)"; done
took=$(( $(date +%s) - ${EPOCH:-$(date +%s)} ))
failed_units="$(systemctl --failed --no-legend 2>/dev/null | wc -l | tr -d ' ')"
if [ -z "$res" ]; then
  log notice "BOOT-OK host=$HOST kernel=$(uname -r) took=${took}s failed_units=${failed_units:-0}"
else
  log err "BOOT-FAIL host=$HOST kernel=$(uname -r) took=${took}s reason=$res failed_units=${failed_units:-0}"
fi
rm -f "$PENDING"
exit 0
