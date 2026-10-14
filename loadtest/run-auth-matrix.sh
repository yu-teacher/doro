#!/usr/bin/env bash
# auth 시나리오 × 동시성 매트릭스를 실행하고 results/auth-matrix.md 에 표로 모은다.
#   ./loadtest/run-auth-matrix.sh            (저장소 루트에서)
set -uo pipefail
cd "$(dirname "$0")"
OUT=results/auth-matrix.md
RAW=results/raw; mkdir -p "$RAW"; rm -f "$RAW"/*.json
DURATION="${DURATION:-15s}"
# 시나리오:동시성 목록(쉼표):USERS
MATRIX=(
  "lookup:50,200:200"
  "public:50,200:10"
  "login-fail:50,200:300"
  "userinfo:100,300:200"
  "sessions:50,200:200"
  "session-churn:20,50:60"
  "revoke-others:20,50:60"
  "2fa-enroll:10,20:30"
  "2fa-login:20,50:600"
  "admin-read:20,50:200"
  "admin-write:20,50:200"
  "oauth-flow:20,50:60"
  "oauth-refresh:20,50:60"
)
for row in "${MATRIX[@]}"; do
  IFS=: read -r sc levels users <<<"$row"
  for v in ${levels//,/ }; do
    echo "[$(date +%H:%M:%S)] $sc @ $v VUs"
    k6 run --quiet --summary-export "$RAW/$sc-$v.json" --summary-trend-stats "avg,med,p(95),p(99),max" \
      -e SCENARIO="$sc" -e VUS="$v" -e USERS="$users" -e DURATION="$DURATION" auth-suite.js > "$RAW/$sc-$v.log" 2>&1
    echo "   exit=$?"
  done
done
python3 summarize.py "$RAW" > "$OUT"
echo "done -> $OUT"
