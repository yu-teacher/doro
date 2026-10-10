#!/usr/bin/env bash
# 텔레그램으로 메시지 한 통을 보낸다. 배포 결과 알림(.github/workflows/deploy.yml)이 쓴다.
#
#   scripts/notify-telegram.sh "메시지"        # 인자로
#   echo "메시지" | scripts/notify-telegram.sh  # 표준입력으로
#   scripts/notify-telegram.sh --check          # 설정이 있는지만 확인(보내지 않음)
#
# 설정: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID. 환경변수에 있으면 그것을, 없으면 $DORO_ENV_FILE(기본 ~/doro/.env)에서 읽는다.
# - 설정이 없으면 알림을 건너뛰고 0 으로 끝난다(알림이 없다고 배포가 실패하면 안 된다). 전송이 실패해도 0 이다: 알림은 부가 기능이다.
# - 비밀 값(봇 토큰)은 화면·로그에 출력하지 않고 curl 인자(프로세스 목록)에도 싣지 않는다(표준입력 -K - 로 넘긴다).
set -uo pipefail

ENV_FILE="${DORO_ENV_FILE:-$HOME/doro/.env}"
CURL_BIN="${CURL_BIN:-curl}"
MAX_CHARS="${TELEGRAM_MAX_CHARS:-3500}"   # 텔레그램 한 통의 한도(4096)보다 작게 자른다

get_var() { grep -m1 "^$1=" "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true; }
TOKEN="${TELEGRAM_BOT_TOKEN:-$(get_var TELEGRAM_BOT_TOKEN)}"
CHAT_ID="${TELEGRAM_CHAT_ID:-$(get_var TELEGRAM_CHAT_ID)}"

if [ -z "$TOKEN" ] || [ -z "$CHAT_ID" ]; then
  echo "텔레그램 설정(TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID)이 없어 알림을 건너뜁니다."
  exit 0
fi
[ "${1:-}" = "--check" ] && { echo "텔레그램 설정 있음"; exit 0; }

if [ "$#" -gt 0 ]; then MESSAGE="$*"; else MESSAGE="$(cat)"; fi
[ -n "$MESSAGE" ] || { echo "보낼 메시지가 없습니다."; exit 0; }
if [ "${#MESSAGE}" -gt "$MAX_CHARS" ]; then MESSAGE="${MESSAGE:0:$MAX_CHARS}…(잘림)"; fi

if printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$TOKEN" \
    | "$CURL_BIN" -sS -m 15 -o /dev/null -K - --fail --data-urlencode "chat_id=$CHAT_ID" --data-urlencode "text=$MESSAGE" 2>/dev/null; then
  echo "텔레그램 알림을 보냈습니다."
else
  echo "::warning::텔레그램 전송에 실패했습니다(알림만 건너뜁니다)."
fi
exit 0
