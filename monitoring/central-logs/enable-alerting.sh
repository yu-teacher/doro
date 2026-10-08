#!/usr/bin/env bash
# 노트북의 ~/central-logs 에서 실행한다: Grafana 알림(텔레그램, 규칙 4개)을 켠다.
#   ./enable-alerting.sh            # 켜기(이미 켜져 있어도 설정을 다시 반영)
#   ./enable-alerting.sh --disable  # 끄기(알림 설정을 비우고 Grafana 재시작)
# .env 에 TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, SSH_ALLOWED_SRC_REGEX 가 모두 있어야 켠다. 값은 화면에 출력하지 않는다.
# 비어 있는 토큰으로 알림 설정을 읽히면 Grafana 가 시작되지 못할 수 있어서, 값이 있을 때만 설정 파일을 활성 폴더에 둔다.
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
mkdir -p alerting-active

if [ "${1:-}" = "--disable" ]; then
  rm -f alerting-active/*.yaml
  docker compose --env-file .env up -d --force-recreate grafana
  echo "알림 설정을 껐다."; exit 0
fi

getv() { grep -E "^$1=" .env | head -1 | cut -d= -f2- | sed -e "s/^'//" -e "s/'$//" -e 's/^"//' -e 's/"$//'; }
missing=()
for k in TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID SSH_ALLOWED_SRC_REGEX; do [ -n "$(getv "$k")" ] || missing+=("$k"); done
[ "${#missing[@]}" -eq 0 ] || { echo "~/central-logs/.env 에 값이 없다: ${missing[*]}" >&2; exit 2; }
grep -Eq '^[0-9]{6,12}:[A-Za-z0-9_-]{30,}$' <<<"$(getv TELEGRAM_BOT_TOKEN)" || { echo "TELEGRAM_BOT_TOKEN 형식이 이상하다(공백/따옴표/줄바꿈 확인)" >&2; exit 2; }

cp -f alerting/*.yaml alerting-active/
# chat ID 는 숫자라서 Grafana 가 $__env 치환 결과를 숫자로 읽어 거부한다(chatid 는 문자열이어야 한다). 활성 파일에만 문자열로 직접 써 넣는다.
# (저장소의 alerting/contact-points.yaml 에는 값이 들어가지 않는다. 토큰은 계속 환경변수로만 전달한다.)
chat_id="$(getv TELEGRAM_CHAT_ID)"
grep -Eq '^-?[0-9]{5,15}$' <<<"$chat_id" || { echo "TELEGRAM_CHAT_ID 형식이 이상하다(숫자여야 한다)" >&2; exit 2; }
sed -i "s|\"\$__env{TELEGRAM_CHAT_ID}\"|\"${chat_id}\"|" alerting-active/contact-points.yaml
grep -q '__env{TELEGRAM_CHAT_ID}' alerting-active/contact-points.yaml && { echo "chat ID 치환에 실패했다" >&2; exit 1; }
# 허용 IP 정규식도 같은 이유로 규칙 쿼리 안에서는 $__env 가 치환되지 않는다(치환 안 된 채 저장되면 모든 로그인이 "처음 보는 IP"로 보여 오탐이 난다).
# 정규식에 쓸 수 있는 문자를 제한해서(따옴표·역슬래시 등 YAML/LogQL 을 깨는 문자 금지) 안전하게 써 넣는다.
src_regex="$(getv SSH_ALLOWED_SRC_REGEX)"
grep -Eq '^[][A-Za-z0-9.|()_-]+$' <<<"$src_regex" || { echo "SSH_ALLOWED_SRC_REGEX 에 쓸 수 없는 문자가 있다(영문·숫자 . | ( ) [ ] _ - 만 허용)" >&2; exit 2; }
SRC_REGEX="$src_regex" python3 - <<'PY'
import os
p = "alerting-active/rules.yaml"
t = open(p, encoding="utf-8").read()
assert "$__env{SSH_ALLOWED_SRC_REGEX}" in t, "허용 IP 자리표시자를 찾지 못했다"
open(p, "w", encoding="utf-8").write(t.replace("$__env{SSH_ALLOWED_SRC_REGEX}", os.environ["SRC_REGEX"]))
PY
grep -q '__env{SSH_ALLOWED_SRC_REGEX}' alerting-active/rules.yaml && { echo "허용 IP 정규식 치환에 실패했다" >&2; exit 1; }
# 이 파일들에는 비밀 값이 없다(토큰은 컨테이너 환경변수 $__env{...} 로만 들어간다). Grafana 는 다른 사용자(uid 472)로 돌아서 읽을 수 있어야 한다.
chmod 644 alerting-active/*.yaml
docker compose --env-file .env up -d --force-recreate grafana
for _ in $(seq 1 30); do
  curl -sf -o /dev/null http://127.0.0.1:"${GRAFANA_PORT:-3300}"/api/health && { echo "Grafana 가 시작됐다. 알림 설정을 반영했다."; exit 0; }
  sleep 2
done
echo "Grafana 가 시작되지 않는다. 로그: docker logs central-logs-grafana-1 (원인이 알림 설정이면 ./enable-alerting.sh --disable)" >&2
exit 1
