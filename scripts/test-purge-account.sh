#!/usr/bin/env bash
# scripts/purge-account.sh 시험: 임시 Postgres 컨테이너로 실제 코드 경로를 돌린다(운영에는 접근하지 않는다).  사용: bash scripts/test-purge-account.sh
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
SCRIPT="$PWD/scripts/purge-account.sh"
T="$(mktemp -d)"; C=purgeacct-test-pg
trap 'docker rm -f "$C" >/dev/null 2>&1 || true; rm -rf "$T"' EXIT
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf '  ok  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  FAIL %s\n' "$1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

docker rm -f "$C" >/dev/null 2>&1 || true
docker run -d --name "$C" -e POSTGRES_PASSWORD=x postgres:16-alpine >/dev/null
for _ in $(seq 1 40); do docker exec "$C" psql -h 127.0.0.1 -U postgres -tAc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
P() { docker exec -i "$C" psql -U postgres -v ON_ERROR_STOP=1 -q -tA "$@"; }
for db in doro_auth service_blog service_party service_games; do P -d postgres -c "create database $db"; done
A1=11111111-1111-4111-8111-111111111111; A2=22222222-2222-4222-8222-222222222222; U1=33333333-3333-4333-8333-333333333333
P -d doro_auth <<SQL
create table users(id uuid primary key, email text, role text, status text, deletion_requested_at timestamptz);
insert into users values ('$A1','admin@doro.local','SUPER_ADMIN','ACTIVE',null),('$A2','yusm@doro.local','SUPER_ADMIN','ACTIVE',null),('$U1','user@doro.com','USER','ACTIVE',null);
SQL
P -d service_blog -c "create table posts(id serial, user_id uuid); create table comments(id serial, user_id uuid); create table series(id serial, user_id uuid); insert into posts(user_id) values ('$A2'),('$A2')"
run() { PG_CONTAINER="$C" PG_USER=postgres BACKUP_DIR="$T/bk" bash "$SCRIPT" "$@" 2>&1; }

echo "== 입력 검증"
out="$(run --check || true)"; check "--email 없으면 거부" 'printf "%s" "$out" | grep -q "필요하다"'
out="$(run --check --email "x'; drop table users;--" || true)"; check "이상한 문자가 든 이메일 거부" 'printf "%s" "$out" | grep -q "올바르지 않다"; [ "$(P -d doro_auth -c "select count(*) from users")" = 3 ]'
out="$(run --check --email nobody@doro.local || true)"; check "없는 계정이면 중단" 'printf "%s" "$out" | grep -q "0개다"'

echo "== --check (읽기 전용)"
out="$(run --check --email yusm@doro.local)"; echo "$out" | sed 's/^/    | /'
check "계정·역할·최고 관리자 수를 보여 준다" 'printf "%s" "$out" | grep -q "SUPER_ADMIN" && printf "%s" "$out" | grep -q "최고 관리자 2 명"'
check "블로그 글 2개를 센다" 'printf "%s" "$out" | grep -q "글 2"'
check "지워도 된다고 안내(아직 안 지움)" 'printf "%s" "$out" | grep -q "지워도 된다" && [ "$(P -d doro_auth -c "select status from users where id=\$\$$A2\$\$")" = ACTIVE ]'
out="$(run --check --email YUSM@DORO.LOCAL)"; check "대소문자 무시로 찾는다" 'printf "%s" "$out" | grep -q "id 22222222"'

echo "== 마지막 최고 관리자 보호"
P -d doro_auth -c "update users set status='SUSPENDED' where id='$A1'"
out="$(run --apply --email yusm@doro.local || true)"
check "마지막 최고 관리자는 거부" 'printf "%s" "$out" | grep -q "마지막 최고 관리자"'
check "거부하면 아무것도 바뀌지 않는다(백업도 없음)" '[ "$(P -d doro_auth -c "select status from users where id=\$\$$A2\$\$")" = ACTIVE ] && ! ls "$T"/bk/purge-account-* >/dev/null 2>&1'
P -d doro_auth -c "update users set status='ACTIVE' where id='$A1'"

echo "== --apply"
out="$(run --apply --email yusm@doro.local)"; echo "$out" | sed 's/^/    | /'
check "탈퇴 대기로 바뀌고 유예가 만료된 시각이 들어갔다" '[ "$(P -d doro_auth -c "select status from users where id=\$\$$A2\$\$")" = PENDING_DELETION ] && [ "$(P -d doro_auth -c "select (now() - deletion_requested_at) > interval \$\$30 days\$\$ from users where id=\$\$$A2\$\$")" = t ]'
check "백업이 만들어졌고 권한이 600 이다" 'f=$(ls "$T"/bk/purge-account-doro_auth-*.sql.gz) && [ -s "$f" ] && [ "$(stat -c %a "$f" 2>/dev/null || stat -f %Lp "$f")" = 600 ]'
check "다른 계정은 그대로다" '[ "$(P -d doro_auth -c "select string_agg(status, \$\$,\$\$ order by email) from users where id in (\$\$$A1\$\$,\$\$$U1\$\$)")" = "ACTIVE,ACTIVE" ]'
out="$(run --apply --email yusm@doro.local)"; check "다시 실행하면 할 일 없음" 'printf "%s" "$out" | grep -q "상태가 PENDING_DELETION 라 할 일 없음"'

echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ] && echo "모두 통과" || exit 1
