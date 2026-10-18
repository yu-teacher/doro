#!/usr/bin/env bash
# install-guard-reconcile-cron.sh 를 가짜 HOME 과 가짜 crontab(PATH 의 셸 shim)으로 시험한다. 실제 crontab 은 건드리지 않는다.
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL="$HERE/install-guard-reconcile-cron.sh"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
FAKE_HOME="$WORK/home"; mkdir -p "$FAKE_HOME" "$WORK/bin"
FAILURES=0
pass() { echo "  ok  $1"; }
fail() { echo "  FAIL $1"; FAILURES=$((FAILURES + 1)); }
eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (기대 '$3', 실제 '$2')"; fi; }

# 가짜 crontab: -l 은 파일 내용(없으면 "no crontab" + 1), - 는 표준입력을 파일로. FAKE_CRONTAB_FAIL 이면 다른 이유로 실패한다.
cat > "$WORK/bin/crontab" <<'SH'
#!/usr/bin/env bash
F="$FAKE_CRONTAB_FILE"
[ -z "${FAKE_CRONTAB_FAIL:-}" ] || { echo "crontab: cannot open crontab spool" >&2; exit 1; }
case "${1:-}" in
  -l) [ -f "$F" ] || { echo "no crontab for tester" >&2; exit 1; }; cat "$F" ;;
  -)  cat > "$F" ;;
  *) exit 2 ;;
esac
SH
chmod +x "$WORK/bin/crontab"
export FAKE_CRONTAB_FILE="$WORK/crontab.txt"

# inst <인자...>: 운영과 같은 방식(stdin 으로 스크립트)으로 실행. 출력은 $WORK/out, 종료 코드는 $CODE.
inst() { set +e; env HOME="$FAKE_HOME" PATH="$WORK/bin:$PATH" NOTIFY_CMD="$WORK/bin/no-notify" bash -s -- "$@" < "$INSTALL" > "$WORK/out" 2>&1; CODE=$?; set -e; }
SRC="$WORK/guard-reconcile.sh"; cp "$HERE/guard-reconcile.sh" "$SRC"
PROD_CRON='0 3 * * * /home/x/backup_db.sh
30 3 * * * /home/x/ops/backup.sh backup >> /home/x/backups/auto/backup.log 2>&1
0 5 * * 0 /home/x/ops/backup.sh verify-restore
0 4 * * 1 certbot renew -q'

echo "== 설치 전 --check (읽기 전용)"
inst --check
eq "종료 코드 0" "$CODE" 0
grep -q '등록 안 됨' "$WORK/out" && grep -q '설치 안 됨' "$WORK/out" && pass "미설치로 보인다" || fail "미설치 출력"
[ ! -e "$FAKE_HOME/ops" ] && [ ! -e "$FAKE_CRONTAB_FILE" ] && pass "아무것도 만들지 않았다" || fail "check 가 파일을 만들었다"

echo "== 인자 오류 / 원본 없음 / crontab 읽기 실패"
inst; eq "인자 없음 = 종료 코드 2" "$CODE" 2
inst --apply --src "$WORK/none.sh"; [ "$CODE" != 0 ] && pass "원본이 없으면 실패" || fail "원본 없는데 성공"
[ ! -e "$FAKE_CRONTAB_FILE" ] && pass "crontab 은 그대로" || fail "crontab 생성"
FAKE_CRONTAB_FAIL=1 inst --apply --src "$SRC"; [ "$CODE" != 0 ] && grep -q 'crontab 을 읽지 못했다' "$WORK/out" && pass "crontab 읽기 실패 시 중단(덮어쓰지 않음)" || fail "crontab 실패 처리"

echo "== 기존 항목이 있는 crontab 에 설치"
printf '%s\n' "$PROD_CRON" > "$FAKE_CRONTAB_FILE"
inst --apply --src "$SRC"
eq "종료 코드 0" "$CODE" 0
[ -x "$FAKE_HOME/ops/guard-reconcile.sh" ] && cmp -s "$SRC" "$FAKE_HOME/ops/guard-reconcile.sh" && pass "스크립트가 ~/ops 에 설치됐다" || fail "스크립트 설치"
eq "기존 항목 4줄은 그대로 맨 앞에" "$(head -4 "$FAKE_CRONTAB_FILE")" "$PROD_CRON"
eq "우리 줄은 한 줄만" "$(grep -c 'guard-reconcile.sh --auto' "$FAKE_CRONTAB_FILE")" 1
grep -q "^30 4 \* \* \* flock -n $FAKE_HOME/backups/auto/.guard-reconcile.lock $FAKE_HOME/ops/guard-reconcile.sh --auto >/dev/null 2>&1$" "$FAKE_CRONTAB_FILE" && pass "04:30, flock, --auto 줄" || fail "cron 줄: $(tail -3 "$FAKE_CRONTAB_FILE")"
eq "마커가 짝으로 있다" "$(grep -c 'doro guard-reconcile' "$FAKE_CRONTAB_FILE")" 2
ls "$FAKE_HOME"/backups/crontab-before-guard-reconcile-*.txt >/dev/null 2>&1 && pass "바꾸기 전 crontab 을 저장했다" || fail "crontab 저장 없음"
eq "저장본 = 원래 crontab" "$(cat "$FAKE_HOME"/backups/crontab-before-guard-reconcile-*.txt | head -4)" "$PROD_CRON"

echo "== 멱등: 다시 설치해도 변하지 않는다"
h1="$(shasum "$FAKE_CRONTAB_FILE" | cut -d' ' -f1)"; ls "$FAKE_HOME/ops" > "$WORK/ls1"; nb="$(ls "$FAKE_HOME"/backups | wc -l | tr -d ' ')"
inst --apply --src "$SRC"
eq "crontab 해시 동일" "$(shasum "$FAKE_CRONTAB_FILE" | cut -d' ' -f1)" "$h1"
grep -q 'crontab: 변경 없음' "$WORK/out" && grep -q '스크립트: 변경 없음' "$WORK/out" && pass "'변경 없음' 출력" || fail "변경 없음 출력"
eq "백업 파일이 더 생기지 않았다" "$(ls "$FAKE_HOME"/backups | wc -l | tr -d ' ')" "$nb"
eq "~/ops 에 .bak 이 없다" "$(ls "$FAKE_HOME/ops")" "guard-reconcile.sh"

echo "== 원본이 바뀌면 스크립트만 백업 후 교체"
echo '# changed' >> "$SRC"
inst --apply --src "$SRC"
cmp -s "$SRC" "$FAKE_HOME/ops/guard-reconcile.sh" && pass "새 버전으로 교체" || fail "교체 안 됨"
ls "$FAKE_HOME"/ops/guard-reconcile.sh.bak-* >/dev/null 2>&1 && pass "기존 파일을 .bak 로 남겼다" || fail ".bak 없음"
eq "crontab 은 그대로" "$(shasum "$FAKE_CRONTAB_FILE" | cut -d' ' -f1)" "$h1"

echo "== --check (설치 후)"
mkdir -p "$FAKE_HOME/backups/auto"; printf 'OK 2026-10-18T04:30:00+0900 c1=0 deleted=0\nWARN 2026-10-19T04:30:00+0900 c3=2\n' > "$FAKE_HOME/backups/auto/guard-reconcile.log"
inst --check
grep -q '등록됨' "$WORK/out" && grep -q '다음 실행: [0-9-]* 04:30' "$WORK/out" && grep -q 'WARN 2026-10-19' "$WORK/out" && pass "등록·다음 실행·마지막 로그가 보인다" || fail "check 출력: $(cat "$WORK/out")"
grep -q 'syslog' "$WORK/out" && pass "알림 경로를 알려 준다" || fail "알림 경로"
eq "check 는 crontab 을 바꾸지 않는다" "$(shasum "$FAKE_CRONTAB_FILE" | cut -d' ' -f1)" "$h1"

echo "== 마커가 짝이 안 맞으면 중단"
cp "$FAKE_CRONTAB_FILE" "$WORK/good"; grep -v '^# <<<' "$WORK/good" > "$FAKE_CRONTAB_FILE"; h2="$(shasum "$FAKE_CRONTAB_FILE" | cut -d' ' -f1)"
inst --apply --src "$SRC"; [ "$CODE" != 0 ] && pass "중단" || fail "짝 안 맞는데 성공"
eq "crontab 그대로" "$(shasum "$FAKE_CRONTAB_FILE" | cut -d' ' -f1)" "$h2"
cp "$WORK/good" "$FAKE_CRONTAB_FILE"

echo "== --remove 는 우리 블록만 지운다"
inst --remove
eq "종료 코드 0" "$CODE" 0
eq "원래 crontab 으로 정확히 돌아갔다" "$(cat "$FAKE_CRONTAB_FILE")" "$PROD_CRON"
[ -x "$FAKE_HOME/ops/guard-reconcile.sh" ] && [ -f "$FAKE_HOME/backups/auto/guard-reconcile.log" ] && pass "스크립트·로그는 남겨 둔다" || fail "스크립트/로그가 지워졌다"
inst --remove; grep -q '할 일 없음' "$WORK/out" && pass "다시 --remove 해도 변화 없음(멱등)" || fail "remove 멱등"
eq "crontab 그대로" "$(cat "$FAKE_CRONTAB_FILE")" "$PROD_CRON"

echo "== 빈 crontab 에 설치하고 제거"
rm -f "$FAKE_CRONTAB_FILE"; inst --apply --src "$SRC"
eq "블록만 있다(4줄: 마커 2 + 줄 1 = 3줄)" "$(wc -l < "$FAKE_CRONTAB_FILE" | tr -d ' ')" 3
inst --remove; eq "제거하면 빈 crontab" "$(cat "$FAKE_CRONTAB_FILE")" ""

echo
if [ "$FAILURES" -eq 0 ]; then echo "모두 통과"; else echo "실패 $FAILURES 건"; exit 1; fi
