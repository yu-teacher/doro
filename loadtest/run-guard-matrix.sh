#!/usr/bin/env bash
# Guard 시나리오 × 동시성 매트릭스를 실행하고 results/guard-matrix.md 에 표로 모은다.
set -uo pipefail
cd "$(dirname "$0")"
OUT=results/guard-matrix.md
RAW=results/raw-guard; mkdir -p "$RAW"; rm -f "$RAW"/*.json
DURATION="${DURATION:-20s}"
# 시나리오:동시성(쉼표):시드 수
MATRIX=(
  "check-hit:50,200:2000"
  "check-miss:50,200:30000"
  "check-deep:50,200:2000"
  "check-cycle:50,200:2000"
  "schema:50,200:100"
  "write-race:50,200:100"
  "write-check:50,200:100"
  "mixed:50,200:5000"
  "grpc-check:50,200:30000"
  "grpc-expand:50,200:2000"
)
for row in "${MATRIX[@]}"; do
  IFS=: read -r sc levels seed <<<"$row"
  for v in ${levels//,/ }; do
    echo "[$(date +%H:%M:%S)] $sc @ $v VUs"
    k6 run --quiet --summary-export "$RAW/$sc-$v.json" --summary-trend-stats "avg,med,p(95),p(99),max" \
      -e SCENARIO="$sc" -e VUS="$v" -e SEED="$seed" -e DURATION="$DURATION" guard-suite.js > "$RAW/$sc-$v.log" 2>&1
    echo "   exit=$?"
  done
done
python3 summarize.py "$RAW" > "$OUT"
echo "done -> $OUT"
