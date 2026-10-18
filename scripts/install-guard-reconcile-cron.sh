#!/usr/bin/env bash
# Guard 정합성 점검(guard-reconcile.sh --auto)을 서버의 사용자 crontab 에 매일 새벽 한 줄로 건다. 운영 서버(mini)에서 실행한다.
# 비밀 값은 다루지 않는다. 멱등: 몇 번 돌려도 결과가 같고, 바뀐 게 없으면 아무것도 쓰지 않는다.
#
#   ssh mini 'bash -s -- --check'  < scripts/install-guard-reconcile-cron.sh   # 읽기 전용: 설치 상태, 다음 실행 시각, 마지막 로그, 알림 경로
#   ssh mini 'bash -s -- --apply'  < scripts/install-guard-reconcile-cron.sh   # 설치(스크립트 복사 + crontab 한 블록)
#   ssh mini 'bash -s -- --remove' < scripts/install-guard-reconcile-cron.sh   # crontab 블록만 제거(설치한 스크립트·로그는 남긴다)
#
# 설치할 스크립트의 원본은 --src PATH (기본: 서버의 저장소 체크아웃 ~/doro/scripts/guard-reconcile.sh).
# 없으면 먼저 올린다:  scp scripts/guard-reconcile.sh mini:/tmp/guard-reconcile.sh  후  --apply --src /tmp/guard-reconcile.sh
#
# - 설치 위치 ~/ops/guard-reconcile.sh (기존 파일이 다르면 .bak-시각 으로 백업 후 교체)
# - crontab: 마커 주석 두 줄 사이에 우리 줄만 둔다. 마커 밖의 기존 항목은 한 글자도 건드리지 않는다. 바꾸기 전에 현재 crontab 을 ~/backups/ 에 저장한다.
# - 시각 04:30: 03:00 DB 백업, 03:30 전체 백업(backup.sh)이 끝난 뒤라 지우기 전에 최신 백업이 있고, 월 04:00 certbot·일 05:00 복원 시험·05:10 자동 재부팅과 겹치지 않는다.
# - 중복 실행은 flock -n 으로 막는다(backup.sh 와 같은 방식). 로그: ~/backups/auto/guard-reconcile.log (1MB 넘으면 .1 로 교체).
set -Eeuo pipefail

ACTION=""; SRC=""
while [ $# -gt 0 ]; do
  case "$1" in
    --check|--apply|--remove) ACTION="$1" ;;
    --src) SRC="${2:-}"; shift ;;
    *) echo "사용법: bash -s -- --check | --apply [--src PATH] | --remove" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$ACTION" ] || { echo "사용법: bash -s -- --check | --apply [--src PATH] | --remove" >&2; exit 2; }

OPS_DIR="${OPS_DIR:-$HOME/ops}"
TARGET="$OPS_DIR/guard-reconcile.sh"
SRC="${SRC:-$HOME/doro/scripts/guard-reconcile.sh}"
LOG_DIR="${LOG_DIR:-$HOME/backups/auto}"
LOG="$LOG_DIR/guard-reconcile.log"
LOCK="$LOG_DIR/.guard-reconcile.lock"
CRON_BACKUP_DIR="${CRON_BACKUP_DIR:-$HOME/backups}"
SCHEDULE_MIN="${SCHEDULE_MIN:-30}"; SCHEDULE_HOUR="${SCHEDULE_HOUR:-4}"
BEGIN='# >>> doro guard-reconcile (managed by scripts/install-guard-reconcile-cron.sh; do not edit between markers)'
END='# <<< doro guard-reconcile'
CRON_LINE="$SCHEDULE_MIN $SCHEDULE_HOUR * * * flock -n $LOCK $TARGET --auto >/dev/null 2>&1"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
STAMP="$(date +%Y%m%d-%H%M%S)"

# 현재 crontab (없으면 빈 문자열). 없음 말고 다른 이유로 실패하면 중단한다.
current_cron() {
  local out rc=0
  out="$(crontab -l 2>&1)" || rc=$?
  if [ "$rc" -ne 0 ]; then
    case "$out" in *"no crontab"*) out="" ;; *) die "crontab 을 읽지 못했다: $out" ;; esac
  fi
  printf '%s' "$out"
}
# 마커 블록을 뺀 나머지(기존 항목 그대로). 마커가 짝이 안 맞으면 중단한다.
strip_block() {
  local text="$1" b e
  b="$(printf '%s\n' "$text" | grep -cxF "$BEGIN" || true)"; e="$(printf '%s\n' "$text" | grep -cxF "$END" || true)"
  [ "$b" = "$e" ] && [ "$b" -le 1 ] || die "crontab 의 마커가 짝이 맞지 않는다(시작 $b, 끝 $e). 손으로 확인하라"
  printf '%s\n' "$text" | awk -v b="$BEGIN" -v e="$END" '$0==b{skip=1;next} $0==e{skip=0;next} !skip{print}'
}
has_block() { printf '%s\n' "$1" | grep -qxF "$BEGIN"; }
trim_trailing_blank() { awk '{a[NR]=$0} END{n=NR; while(n>0 && a[n]=="") n--; for(i=1;i<=n;i++) print a[i]}'; }

next_run() {
  local day="today" now_hm target_hm
  now_hm="$(date +%H%M)"; target_hm="$(printf '%02d%02d' "$((10#$SCHEDULE_HOUR))" "$((10#$SCHEDULE_MIN))")"
  [ "$((10#$now_hm))" -lt "$((10#$target_hm))" ] || day="tomorrow"
  if date -d "$day" +%F >/dev/null 2>&1; then
    printf '%s %02d:%02d' "$(date -d "$day" +%F)" "$((10#$SCHEDULE_HOUR))" "$((10#$SCHEDULE_MIN))"
  else
    [ "$day" = today ] && printf '%s' "$(date +%F)" || printf '%s' "$(date -v+1d +%F)"
    printf ' %02d:%02d' "$((10#$SCHEDULE_HOUR))" "$((10#$SCHEDULE_MIN))"
  fi
}

do_check() {
  local cron; cron="$(current_cron)"
  log "== 점검(읽기 전용) =="
  if [ -x "$TARGET" ]; then
    if [ -f "$SRC" ] && cmp -s "$SRC" "$TARGET"; then log "스크립트: $TARGET 설치됨(원본과 같음)"
    elif [ -f "$SRC" ]; then log "스크립트: $TARGET 설치됨, 원본($SRC)과 다름 -> --apply 로 갱신"
    else log "스크립트: $TARGET 설치됨(원본 $SRC 없음)"; fi
  else
    log "스크립트: 설치 안 됨 (원본 $SRC $([ -f "$SRC" ] && echo 있음 || echo 없음))"
  fi
  if has_block "$cron"; then
    log "crontab: 등록됨 -> $(printf '%s\n' "$cron" | awk -v b="$BEGIN" -v e="$END" '$0==b{f=1;next} $0==e{f=0} f')"
    log "다음 실행: $(next_run)"
  else
    log "crontab: 등록 안 됨"
  fi
  if [ -f "$LOG" ]; then
    log "마지막 로그 5줄($LOG, $(wc -c < "$LOG" | tr -d ' ')바이트):"; tail -n 5 "$LOG" | sed 's/^/    /'
  else
    log "로그 없음($LOG): 아직 한 번도 실행되지 않았다"
  fi
  local n="${NOTIFY_CMD:-$HOME/doro/scripts/notify-telegram.sh}"
  if [ -x "$n" ]; then log "알림: ALERT/ABORT 는 syslog(doro-guard-reconcile -> Loki)와 텔레그램($("$n" --check 2>&1 | head -1))으로 나간다"
  else log "알림: 텔레그램 스크립트($n)가 없어 syslog(doro-guard-reconcile -> Loki)로만 나간다"; fi
}

write_cron() { # <새 내용> : 바뀐 게 없으면 쓰지 않는다. 쓰기 전에 현재 crontab 을 저장한다
  local new="$1" old="$2"
  if [ "$new" = "$old" ]; then log "crontab: 변경 없음"; return 0; fi
  mkdir -p "$CRON_BACKUP_DIR"
  printf '%s\n' "$old" > "$CRON_BACKUP_DIR/crontab-before-guard-reconcile-$STAMP.txt"; chmod 600 "$CRON_BACKUP_DIR/crontab-before-guard-reconcile-$STAMP.txt"
  printf '%s\n' "$new" | crontab - || die "crontab 설치 실패"
  log "crontab: 갱신(이전 내용 저장: $CRON_BACKUP_DIR/crontab-before-guard-reconcile-$STAMP.txt)"
}

do_apply() {
  [ -f "$SRC" ] || die "원본 스크립트가 없다: $SRC (scp 로 올린 뒤 --src 로 지정하라)"
  bash -n "$SRC" || die "원본 스크립트 문법 오류"
  grep -q -- '--auto' "$SRC" || die "원본에 --auto 모드가 없다(오래된 버전)"
  mkdir -p "$OPS_DIR" "$LOG_DIR"
  if [ -f "$TARGET" ] && cmp -s "$SRC" "$TARGET"; then
    log "스크립트: 변경 없음($TARGET)"
  else
    if [ -f "$TARGET" ]; then cp -p "$TARGET" "$TARGET.bak-$STAMP"; log "스크립트: 기존 파일을 $TARGET.bak-$STAMP 로 백업"; fi
    cp "$SRC" "$TARGET.new.$$"; chmod 755 "$TARGET.new.$$"; mv -f "$TARGET.new.$$" "$TARGET"
    log "스크립트: $TARGET 설치"
  fi
  local old new rest
  old="$(current_cron)"
  rest="$(strip_block "$old" | trim_trailing_blank)"
  new="$(printf '%s\n%s\n%s\n%s' "$rest" "$BEGIN" "$CRON_LINE" "$END" | awk 'NR==1 && $0=="" {next} {print}')"
  old="$(printf '%s' "$old" | trim_trailing_blank)"
  write_cron "$new" "$old"
  log "다음: --check 로 확인. 로그: $LOG"
}

do_remove() {
  local old rest
  old="$(current_cron)"
  if ! has_block "$old"; then log "crontab 에 우리 블록이 없다. 할 일 없음"; return 0; fi
  rest="$(strip_block "$old" | trim_trailing_blank)"
  old="$(printf '%s' "$old" | trim_trailing_blank)"
  write_cron "$rest" "$old"
  log "제거 완료(설치된 스크립트·로그·백업은 남겨 둔다)"
}

case "$ACTION" in --check) do_check ;; --apply) do_apply ;; --remove) do_remove ;; esac
