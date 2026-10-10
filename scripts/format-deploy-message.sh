#!/usr/bin/env bash
# 배포 결과 텔레그램 메시지를 만든다(.github/workflows/deploy.yml 의 notify 작업이 notify-telegram.sh 로 보낸다).
# 입력은 환경변수: TEST_RESULT, DEPLOY_RESULT(success|failure|skipped|cancelled), SMOKE(배포 후 점검 출력), SHA, COMMIT_MSG, ACTOR, RUN_URL
# 커밋 메시지처럼 사용자가 정한 문자열은 셸에 직접 끼워 넣지 않고 환경변수로만 받는다(스크립트 주입 방지).
set -uo pipefail

TEST="${TEST_RESULT:-unknown}"; DEPLOY="${DEPLOY_RESULT:-unknown}"
SHORT="${SHA:-unknown}"; SHORT="${SHORT:0:7}"
SUBJECT="$(printf '%s' "${COMMIT_MSG:-}" | head -1)"
[ "${#SUBJECT}" -gt 90 ] && SUBJECT="${SUBJECT:0:90}…"
SMOKE_TEXT="${SMOKE:-}"
SUMMARY_LINE="$(printf '%s\n' "$SMOKE_TEXT" | grep -E '^점검 [0-9]+개 중' | tail -1)"
FAILED_LINES="$(printf '%s\n' "$SMOKE_TEXT" | grep '^❌' | head -8)"

if [ "$TEST" = failure ]; then
  HEAD="❌ 테스트 실패 — 배포하지 않았습니다 (서버는 이전 상태 그대로)"
elif [ "$DEPLOY" = success ]; then
  HEAD="✅ 배포 완료"
elif [ "$DEPLOY" = failure ]; then
  HEAD="❌ 배포 또는 배포 후 점검 실패 — 서버가 일부만 반영됐을 수 있습니다"
elif [ "$DEPLOY" = skipped ]; then
  HEAD="ℹ️ 배포를 건너뛰었습니다"
else
  HEAD="⚠️ 배포 결과를 알 수 없습니다 (테스트 $TEST, 배포 $DEPLOY)"
fi

echo "$HEAD"
echo "${SHORT} ${SUBJECT}"
[ -n "${ACTOR:-}" ] && echo "푸시: $ACTOR"
if [ "$DEPLOY" = success ] && [ -n "$SUMMARY_LINE" ]; then echo "$SUMMARY_LINE"; fi
if [ -n "$FAILED_LINES" ]; then echo; echo "$FAILED_LINES"; fi
[ -n "${RUN_URL:-}" ] && { echo; echo "$RUN_URL"; }
exit 0
