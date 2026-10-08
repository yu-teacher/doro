#!/usr/bin/env bash
# 외부 감시(워치독): 파이에서 주기적으로 미니·노트북·로그 수집 상태를 확인하고, 이상이 이어지면 알림을 보낸다.
# 미니가 죽으면 미니 안의 감시(Grafana 등)도 같이 멈추므로, 별도 장비(파이)에서 바깥을 본다.
#
#   ~/watchdog/watchdog.sh             # 한 번 점검 (cron 이 2분마다 실행)
#   ~/watchdog/watchdog.sh --verbose   # 점검 결과를 화면에도 출력
#   ~/watchdog/watchdog.sh --status    # 마지막 점검 결과만 출력(점검하지 않음)
#   ~/watchdog/watchdog.sh --find-chat-id    # 텔레그램 봇에게 말을 건 뒤 실행: 내 chat ID 를 찾아 출력(토큰은 .env 에서 읽는다)
#   ~/watchdog/watchdog.sh --test-telegram   # 설정한 텔레그램으로 시험 메시지 한 통을 보낸다
#
# - 결과는 syslog(logger, 태그 doro-watchdog)로 남긴다 -> journal -> 중앙 Loki 로 수집된다. 파일에 직접 쓰지 않는다.
# - 일시적 실패에 알림이 울리지 않게, 연속 CONFIRM_FAILS 번 실패해야 알린다. 계속 실패하면 REMIND_HOURS 마다 다시 알린다.
# - 설정은 ~/watchdog/.env (.env.example 참고). 텔레그램 값이 없으면 알림 없이 기록만 한다.
# - 비밀 값(봇 토큰)은 로그·화면에 출력하지 않고, curl 인자(프로세스 목록)에도 싣지 않는다.
set -uo pipefail

DIR="${WATCHDOG_DIR:-$HOME/watchdog}"
ENV_FILE="${WATCHDOG_ENV:-$DIR/.env}"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && . "$ENV_FILE"

MODE="run"; VERBOSE=false
for a in "$@"; do case "$a" in --verbose) VERBOSE=true ;; --status) MODE="status" ;; --find-chat-id) MODE="find-chat-id" ;; --test-telegram) MODE="test-telegram" ;; esac; done

# 텔레그램 설정 도우미: 토큰은 표준입력(curl -K -)으로만 넘기고, 화면과 로그에는 출력하지 않는다.
tg_call() { # <메서드> [curl 추가 인자...]
  local method="$1"; shift
  printf 'url = "https://api.telegram.org/bot%s/%s"\n' "$TELEGRAM_BOT_TOKEN" "$method" | curl -sS -m 15 -K - "$@"
}
case "$MODE" in
  find-chat-id)
    [ -n "${TELEGRAM_BOT_TOKEN:-}" ] || { echo "~/watchdog/.env 에 TELEGRAM_BOT_TOKEN 을 먼저 넣으세요." >&2; exit 2; }
    tg_call getUpdates | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("응답을 읽지 못했습니다. 토큰이 맞는지, 인터넷 연결이 되는지 확인하세요."); sys.exit(1)
if not d.get("ok"):
    print("텔레그램이 요청을 거절했습니다(토큰이 틀렸을 수 있습니다):", d.get("description", "")); sys.exit(1)
seen = {}
for u in d.get("result", []):
    m = u.get("message") or u.get("edited_message") or u.get("channel_post") or {}
    c = m.get("chat")
    if c:
        seen[c["id"]] = (c.get("type"), c.get("first_name") or c.get("title") or c.get("username") or "")
if not seen:
    print("받은 메시지가 없습니다. 텔레그램에서 내 봇과 대화를 열고 Start 를 누른 뒤 아무 메시지를 보내고 다시 실행하세요.")
    sys.exit(1)
for cid, (ctype, name) in seen.items():
    print(f"chat id: {cid}   (종류: {ctype}, 이름: {name})")
print("→ 본인 대화(종류 private)의 숫자를 ~/watchdog/.env 의 TELEGRAM_CHAT_ID 에 넣으세요.")
'
    exit $? ;;
  test-telegram)
    { [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; } || { echo "~/watchdog/.env 에 TELEGRAM_BOT_TOKEN 과 TELEGRAM_CHAT_ID 를 먼저 넣으세요." >&2; exit 2; }
    tg_call sendMessage --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" --data-urlencode "text=✅ 워치독 알림 시험입니다 ($(hostname), $(date '+%F %T'))" \
      | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("응답을 읽지 못했습니다."); sys.exit(1)
print("전송 성공: 휴대폰/앱에서 메시지를 확인하세요." if d.get("ok") else "전송 실패: " + str(d.get("description", "")))
sys.exit(0 if d.get("ok") else 1)
'
    exit $? ;;
esac

: "${MINI_HOST:?설정 필요: MINI_HOST}" "${NOTEBOOK_HOST:?설정 필요: NOTEBOOK_HOST}" "${PUBLIC_HOST:?설정 필요: PUBLIC_HOST}" "${LOKI_URL:?설정 필요: LOKI_URL}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/doro-backups}"
BACKUP_MAX_AGE_HOURS="${BACKUP_MAX_AGE_HOURS:-30}"
CERT_WARN_DAYS="${CERT_WARN_DAYS:-21}"
CERT_CRIT_DAYS="${CERT_CRIT_DAYS:-7}"
LOGS_MAX_AGE_MIN="${LOGS_MAX_AGE_MIN:-10}"
CONFIRM_FAILS="${CONFIRM_FAILS:-2}"
REMIND_HOURS="${REMIND_HOURS:-6}"
HTTP_TIMEOUT_SEC="${HTTP_TIMEOUT_SEC:-10}"
AUDIT_DIR="${AUDIT_DIR:-$HOME/audit-archive}"
AUDIT_PULL="${AUDIT_PULL:-$AUDIT_DIR/audit-pull.py}"
AUDIT_MAX_AGE_HOURS="${AUDIT_MAX_AGE_HOURS:-3}"
STATE="$DIR/state"
NOW="$(date +%s)"
mkdir -p "$STATE"

if [ "$MODE" = "status" ]; then
  for f in "$STATE"/*; do
    [ -f "$f" ] || continue
    ( . "$f"; printf '%-22s %-5s fails=%s since=%s %s\n' "$(basename "$f")" "$STATUS" "$FAILS" "$(date -d "@$SINCE" '+%F %T')" "$LAST_MSG" )
  done
  exit 0
fi

exec 9>"$DIR/.lock"; flock -n 9 || exit 0

syslog() { logger -t doro-watchdog -p "user.$1" -- "${@:2}"; }

# 텔레그램 알림. 설정이 없으면 기록만 한다. 토큰은 표준입력(curl -K -)으로 넘긴다.
notify() {
  syslog notice "ALERT: $*"
  if [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
    if printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$TELEGRAM_BOT_TOKEN" \
        | curl -sS -m 15 -o /dev/null -K - --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" --data-urlencode "text=$*" 2>/dev/null; then
      return 0
    fi
    syslog err "텔레그램 전송 실패"
  fi
  return 0
}

fmt_duration() { local s="$1"; if [ "$s" -ge 3600 ]; then echo "$((s / 3600))시간 $((s % 3600 / 60))분"; elif [ "$s" -ge 60 ]; then echo "$((s / 60))분"; else echo "${s}초"; fi; }

OK_N=0; WARN_N=0; FAIL_N=0
# record <이름> <ok|warn|fail> <메시지>: 상태 전이를 판단해 알림/기록을 남긴다.
record() {
  local name="$1" status="$2" msg="$3" f="$STATE/$1"
  local STATUS="ok" FAILS=0 SINCE="$NOW" NOTIFIED=0 LAST_NOTIFY=0 LAST_MSG=""
  # shellcheck disable=SC1090
  [ -f "$f" ] && . "$f"
  case "$status" in ok) OK_N=$((OK_N + 1)) ;; warn) WARN_N=$((WARN_N + 1)) ;; *) FAIL_N=$((FAIL_N + 1)) ;; esac
  $VERBOSE && printf '%-22s %-5s %s\n' "$name" "$status" "$msg"

  if [ "$status" = ok ]; then
    if [ "$NOTIFIED" = 1 ]; then notify "🟢 복구: $name ($(fmt_duration $((NOW - SINCE))) 동안 이상) - $msg"; fi
    [ "$STATUS" != ok ] && syslog info "$name 정상으로 돌아옴: $msg"
    STATUS=ok; FAILS=0; SINCE="$NOW"; NOTIFIED=0
  else
    [ "$STATUS" = ok ] && SINCE="$NOW"
    FAILS=$((FAILS + 1))
    [ "$STATUS" != "$status" ] && syslog warning "$name $status: $msg"
    STATUS="$status"
    if [ "$FAILS" -ge "$CONFIRM_FAILS" ] && [ "$NOTIFIED" = 0 ]; then
      notify "$([ "$status" = fail ] && echo '🔴' || echo '🟡') $name: $msg"; NOTIFIED=1; LAST_NOTIFY="$NOW"
    elif [ "$NOTIFIED" = 1 ] && [ $((NOW - LAST_NOTIFY)) -ge $((REMIND_HOURS * 3600)) ]; then
      notify "⏰ 아직 이상: $name ($(fmt_duration $((NOW - SINCE))) 경과) - $msg"; LAST_NOTIFY="$NOW"
    fi
  fi
  LAST_MSG="$msg"
  { printf 'STATUS=%q\nFAILS=%q\nSINCE=%q\nNOTIFIED=%q\nLAST_NOTIFY=%q\nLAST_MSG=%q\n' "$STATUS" "$FAILS" "$SINCE" "$NOTIFIED" "$LAST_NOTIFY" "$LAST_MSG"; } > "$f.tmp" && mv "$f.tmp" "$f"
}

tcp_open() { timeout 4 bash -c "exec 3<>/dev/tcp/$1/$2" 2>/dev/null; }

# ---- 1) 서버 응답
tcp_open "$MINI_HOST" 22     && record mini_ssh ok "SSH 응답" || record mini_ssh fail "미니 SSH(22) 응답 없음"
tcp_open "$NOTEBOOK_HOST" 22 && record notebook_ssh ok "SSH 응답" || record notebook_ssh fail "노트북 SSH(22) 응답 없음"

# ---- 2) 서비스(게이트웨이 경유, 사용자가 보는 경로 그대로)
http_check() { # <이름> <경로> <기대 코드>
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time "$HTTP_TIMEOUT_SEC" --resolve "$PUBLIC_HOST:443:$MINI_HOST" "https://$PUBLIC_HOST$2" 2>/dev/null || true)"
  if [ "$code" = "$3" ]; then record "$1" ok "HTTP $code"; else record "$1" fail "HTTP ${code:-응답없음} (기대 $3): $2"; fi
}
http_check web_portal "/" 200
http_check web_blog_api "/api/v1/posts?page=0&size=1" 200
http_check web_party "/party/" 200

# ---- 3) 인증서 만료
end="$(echo | timeout 10 openssl s_client -connect "$MINI_HOST:443" -servername "$PUBLIC_HOST" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
if [ -n "$end" ]; then
  days=$(( ($(date -d "$end" +%s) - NOW) / 86400 ))
  if   [ "$days" -lt "$CERT_CRIT_DAYS" ]; then record tls_cert fail "인증서 만료 ${days}일 남음 (갱신 확인 필요)"
  elif [ "$days" -lt "$CERT_WARN_DAYS" ]; then record tls_cert warn "인증서 만료 ${days}일 남음"
  else record tls_cert ok "인증서 ${days}일 남음"; fi
else
  record tls_cert fail "인증서를 읽지 못함"
fi

# ---- 4) 백업 최신성(파이가 받은 사본 기준)
latest="$(ls -1 "$BACKUP_DIR/daily" 2>/dev/null | grep -E '^[0-9]{8}-[0-9]{6}$' | sort | tail -1)"
if [ -n "$latest" ]; then
  ts="$(date -d "${latest:0:4}-${latest:4:2}-${latest:6:2} ${latest:9:2}:${latest:11:2}:${latest:13:2}" +%s 2>/dev/null || echo 0)"
  age_h=$(( (NOW - ts) / 3600 ))
  if [ "$age_h" -gt "$BACKUP_MAX_AGE_HOURS" ]; then record backup_fresh fail "받은 최신 백업이 ${age_h}시간 전 ($latest)"; else record backup_fresh ok "최신 백업 ${age_h}시간 전"; fi
else
  record backup_fresh fail "받은 백업이 없음"
fi

# ---- 5) 중앙 로그 저장소와 로그 유입
if [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$LOKI_URL/gateway-health" 2>/dev/null)" = 200 ]; then
  record loki_gateway ok "응답 정상"
  for h in mini notebook pi; do
    n="$(curl -s -G -m 10 "$LOKI_URL/loki/api/v1/query" --data-urlencode "query=sum(count_over_time({host=\"$h\"}[${LOGS_MAX_AGE_MIN}m]))" 2>/dev/null \
        | python3 -c 'import sys,json
try:
    r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)
except Exception:
    print(-1)')"
    if   [ "$n" = "-1" ]; then record "logs_$h" warn "로그 질의 실패"
    elif [ "$n" -gt 0 ]; then record "logs_$h" ok "최근 ${LOGS_MAX_AGE_MIN}분 로그 ${n}건"
    else record "logs_$h" fail "최근 ${LOGS_MAX_AGE_MIN}분 동안 $h 로그가 들어오지 않음(수집기 중단?)"; fi
  done
else
  record loki_gateway fail "중앙 로그 저장소(노트북) 응답 없음"
fi

# ---- 6) 감사 로그 사본(파이가 중앙 Loki 에서 가져온 SSH·sudo 기록)
if [ -f "$AUDIT_DIR/.state.json" ]; then
  age_h=$(( (NOW - $(stat -c %Y "$AUDIT_DIR/.state.json")) / 3600 ))
  if [ "$age_h" -ge "$AUDIT_MAX_AGE_HOURS" ]; then record audit_archive fail "감사 로그 사본이 ${age_h}시간째 갱신되지 않음"; else record audit_archive ok "사본 ${age_h}시간 전 갱신"; fi
  # 무결성 검증은 부하를 줄이려고 1시간에 한 번만 하고, 결과를 캐시해 둔다.
  vf="$DIR/audit_verify.result"
  if [ ! -f "$vf" ] || [ $((NOW - $(stat -c %Y "$vf"))) -ge 3600 ]; then
    out="$(AUDIT_DIR="$AUDIT_DIR" timeout 120 python3 "$AUDIT_PULL" --verify 2>&1)"; rc=$?
    { echo "$rc"; echo "$out" | tail -4 | tr '\n' ' '; echo; } > "$vf"
  fi
  if [ "$(head -1 "$vf")" = 0 ]; then record audit_integrity ok "감사 로그 무결성 검증 통과"; else record audit_integrity fail "감사 로그 사본 검증 실패(변조/손상 가능): $(sed -n 2p "$vf" | cut -c1-200)"; fi
else
  record audit_archive fail "감사 로그 사본이 아직 없음"
fi

syslog info "점검 완료: 정상 $OK_N, 경고 $WARN_N, 실패 $FAIL_N"
[ "$FAIL_N" -eq 0 ] && [ "$WARN_N" -eq 0 ]
