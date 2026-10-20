#!/usr/bin/env bash
# scripts/split-db-accounts.sh 시험: 임시 Postgres 컨테이너와 가짜 HOME, docker 셔심으로 실제 코드 경로를 그대로 돌린다.
# (운영 DB·서버에는 접근하지 않는다.)  사용: bash scripts/test-split-db-accounts.sh
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
SCRIPT="$PWD/scripts/split-db-accounts.sh"
T="$(mktemp -d)"; C=splitdb-test-pg
trap 'docker rm -f "$C" >/dev/null 2>&1 || true; rm -rf "$T"' EXIT
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf '  ok  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  FAIL %s\n' "$1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

docker rm -f "$C" >/dev/null 2>&1 || true
docker run -d --name "$C" -e POSTGRES_PASSWORD=x postgres:16-alpine >/dev/null
for _ in $(seq 1 40); do docker exec "$C" psql -h 127.0.0.1 -U postgres -tAc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
P() { docker exec -i "$C" psql -U postgres -v ON_ERROR_STOP=1 -q -tA "$@"; }
asrole() { docker exec -e PGPASSWORD="$3" "$C" psql -h "$(docker exec "$C" hostname -i | awk '{print $1}')" -U "$1" -d "$2" -tAc "$4" 2>&1 || true; }

for db in service_games service_party service_blog; do P -d postgres -c "create database $db"; done
for db in service_games service_party service_blog; do
  P -d $db <<'SQL'
create extension if not exists pg_trgm;
create type mood as enum ('a','b');
create table items(id serial primary key, name text, m mood);
create view item_names as select name from items;
create function twice(x int) returns int language sql as 'select x*2';
insert into items(name) values ('x');
SQL
done

# 가짜 HOME + docker 셔심(compose 는 흉내, health 는 파일로 제어, 컨테이너 환경은 .env 에서 읽어 재현)
H="$T/home"; mkdir -p "$H/doro-games" "$H/doro-party" "$H/doro-blog" "$T/bin"
for s in games party blog; do
  printf 'DB_USER=postgres\nDB_PASSWORD=x\nOTHER=keep\n' > "$H/doro-$s/.env"
  printf 'services:\n  %s-api:\n    environment:\n      DB_USER: ${DB_USER:-doro_admin}\n' "$s" > "$H/doro-$s/docker-compose.prod.yml"
done
REAL_DOCKER="$(command -v docker)"
cat > "$T/bin/docker" <<EOF
#!/usr/bin/env bash
REAL="$REAL_DOCKER"; H="$H"; T="$T"
if [ "\$1" = compose ]; then
  if [ -f "\$T/fail_after_recreate" ]; then
    if [ -f "\$T/unhealthy" ]; then rm -f "\$T/unhealthy"; else touch "\$T/unhealthy"; fi
  fi
  exit 0
fi
if [ "\$1" = inspect ] && [[ "\$*" == *Health.Status* ]]; then
  if [ -f "\$T/unhealthy" ]; then echo unhealthy; else echo healthy; fi; exit 0
fi
if [ "\$1" = inspect ] && [[ "\$*" == *Config.Env* ]]; then
  case "\$2" in
    doro-games-api) d=doro-games ;; doro-party-api) d=doro-party ;; doro-blog-backend) d=doro-blog ;; *) exit 1 ;;
  esac
  echo "DB_USER=\$(grep -m1 '^DB_USER=' "\$H/\$d/.env" | cut -d= -f2-)"; exit 0
fi
exec "\$REAL" "\$@"
EOF
chmod +x "$T/bin/docker"
run() { PATH="$T/bin:$PATH" HEALTH_TRIES=2 HEALTH_SLEEP=1 HOME="$H" PG_CONTAINER="$C" PG_ADMIN_USER=postgres BACKUP_DIR="$T/bk" bash "$SCRIPT" "$@" 2>&1; }

echo "== 사전 상태"
out="$(run --check)"; check "check 는 서비스 6개를 보여 준다" '[ "$(printf "%s\n" "$out" | grep -c "^\[.*\] \[")" -ge 6 ]'
check "check 는 비밀번호를 출력하지 않는다" '! printf "%s" "$out" | grep -q "DB_PASSWORD="'
check "check 는 아무것도 바꾸지 않는다(.env 그대로)" 'grep -q "^DB_USER=postgres$" "$H/doro-games/.env"'

echo "== games 전환"
out="$(run --apply games)"; echo "$out" | sed 's/^/    | /'
check "계정이 만들어졌다(슈퍼유저 아님)" '[ "$(P -d postgres -c "select rolsuper::int + rolcreatedb::int + rolcreaterole::int + rolreplication::int from pg_roles where rolname=\$\$games_app\$\$")" = 0 ]'
check "DB 소유자가 games_app 이다" '[ "$(P -d postgres -c "select pg_get_userbyid(datdba) from pg_database where datname=\$\$service_games\$\$")" = games_app ]'
check "테이블·시퀀스·뷰·함수·타입 소유자가 games_app 이다" '[ "$(P -d service_games -c "select (select pg_get_userbyid(relowner) from pg_class where relname=\$\$items\$\$)||(select pg_get_userbyid(relowner) from pg_class where relname=\$\$items_id_seq\$\$)||(select pg_get_userbyid(relowner) from pg_class where relname=\$\$item_names\$\$)||(select pg_get_userbyid(proowner) from pg_proc where proname=\$\$twice\$\$)||(select pg_get_userbyid(typowner) from pg_type where typname=\$\$mood\$\$)")" = games_appgames_appgames_appgames_appgames_app ]'
check "확장(pg_trgm) 함수는 소유자를 바꾸지 않았다" '[ "$(P -d service_games -c "select pg_get_userbyid(proowner) from pg_proc where proname=\$\$similarity\$\$ limit 1")" = postgres ]'
pw="$(grep '^DB_PASSWORD=' "$H/doro-games/.env" | cut -d= -f2-)"
check ".env 에 새 계정과 비밀번호가 쓰였고 다른 항목은 그대로다" 'grep -q "^DB_USER=games_app$" "$H/doro-games/.env" && [ ${#pw} -ge 40 ] && grep -q "^OTHER=keep$" "$H/doro-games/.env"'
check ".env 파일 권한이 600 이다" '[ "$(stat -c %a "$H/doro-games/.env" 2>/dev/null || stat -f %Lp "$H/doro-games/.env")" = 600 ]'
check "출력에 비밀번호가 없다" '! printf "%s" "$out" | grep -qF "$pw"'
check "백업이 만들어졌다" 'ls "$T"/bk/dbsplit-service_games-*.sql.gz >/dev/null 2>&1'
check "새 계정으로 자기 DB 에 로그인·변경(ALTER/INDEX/INSERT)이 된다" '[ "$(asrole games_app service_games "$pw" "alter table items add column c int; create index on items(name); insert into items(name) values (\$\$y\$\$); select count(*) from items" | tail -1)" = 2 ]'
check "비밀번호가 틀리면 로그인되지 않는다" '! asrole games_app service_games wrong "select 1" | grep -qx 1'

echo "== 멱등"
out="$(run --apply games)"; check "다시 실행하면 할 일 없음" 'printf "%s" "$out" | grep -q "이미 games_app 로 접속 중"'

echo "== party 전환 후 격리"
run --apply party >/dev/null
ppw="$(grep '^DB_PASSWORD=' "$H/doro-party/.env" | cut -d= -f2-)"
check "games_app 은 party DB 에 접속할 수 없다" 'asrole games_app service_party "$pw" "select 1" | grep -qi "permission denied"'
check "party_app 은 games DB 에 접속할 수 없다" 'asrole party_app service_games "$ppw" "select 1" | grep -qi "permission denied"'
check "슈퍼유저는 여전히 모든 DB 에 접속한다(백업·운영)" '[ "$(P -d service_party -c "select 1")" = 1 ] && [ "$(P -d service_games -c "select 1")" = 1 ]'

echo "== 재생성 뒤 healthy 가 안 되면 .env 를 되돌린다"
touch "$T/fail_after_recreate"
before="$(grep '^DB_USER=' "$H/doro-blog/.env")"
out="$(run --apply blog || true)"
rm -f "$T/fail_after_recreate" "$T/unhealthy"
check "되돌리고 중단했다고 알린다" 'printf "%s" "$out" | grep -q "되돌렸다"'
check ".env 의 DB_USER 가 전환 전 값이다" '[ "$(grep "^DB_USER=" "$H/doro-blog/.env")" = "$before" ]'
check "다른 항목(OTHER)도 그대로다" 'grep -q "^OTHER=keep$" "$H/doro-blog/.env"'
touch "$T/unhealthy"
out="$(run --apply blog || true)"; rm -f "$T/unhealthy"
check "처음부터 unhealthy 면 사전 점검에서 거부한다" 'printf "%s" "$out" | grep -q "healthy 가 아니다"'

echo "== 되돌리기"
out="$(run --rollback games)"
check "rollback 이 .env 를 전환 전으로 돌린다" 'grep -q "^DB_USER=postgres$" "$H/doro-games/.env"'

echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ] && echo "모두 통과" || exit 1
