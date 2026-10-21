#!/usr/bin/env bash
# guard-reconcile.sh 를 일회용 PostgreSQL(컨테이너 guardrec-test-pg)에서 시험한다. 운영 DB 는 건드리지 않는다.
# 실제 서비스 마이그레이션과 같은 컬럼 이름·타입으로 최소한의 가짜 스키마를 만든다. Docker 가 필요하다.
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/guard-reconcile.sh"
CT=guardrec-test-pg
WORK="$(mktemp -d)"
cleanup() { docker rm -f "$CT" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
FAILURES=0
pass() { echo "  ok  $1"; }
fail() { echo "  FAIL $1"; FAILURES=$((FAILURES + 1)); }
eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (기대 '$3', 실제 '$2')"; fi; }

export PG_CONTAINER="$CT" PG_USER=postgres BACKUP_DIR="$WORK/backups"

docker rm -f "$CT" >/dev/null 2>&1 || true
docker run -d --name "$CT" -e POSTGRES_PASSWORD=test postgres:16-alpine >/dev/null
for _ in $(seq 1 60); do
  docker exec "$CT" psql -h 127.0.0.1 -U postgres -tAc 'select 1' >/dev/null 2>&1 && break
  sleep 1
done
sql() { docker exec -i "$CT" psql -U postgres -v ON_ERROR_STOP=1 -q -tA -d "$1"; }

# ---- 가짜 스키마(실제 마이그레이션의 이름·타입)
for db in doro_auth doro_guard service_blog service_party; do docker exec "$CT" psql -U postgres -q -c "create database $db" >/dev/null; done
sql doro_auth <<'SQL'
create table users (id uuid primary key, email varchar(255), status varchar(30) not null default 'ACTIVE', role varchar(30) not null default 'USER');
SQL
sql doro_guard <<'SQL'
create table relation_tuples (
  id uuid primary key, namespace varchar(64) not null, object_id varchar(128) not null, relation varchar(64) not null,
  subject_namespace varchar(64) not null, subject_id varchar(128) not null, subject_relation varchar(64),
  created_at timestamptz not null default current_timestamp);
create unique index uq_relation_tuple on relation_tuples (namespace, object_id, relation, subject_namespace, subject_id, subject_relation);
SQL
sql service_blog <<'SQL'
create table blog_users (id uuid primary key, email varchar(255), profile_image_url text, about_markdown text);
create table series (id uuid primary key, user_id uuid not null, description text, thumbnail_url varchar(500));
create table posts (id uuid primary key, user_id uuid not null, series_id uuid, content text, thumbnail_url varchar(500));
create table comments (id uuid primary key, post_id uuid not null, user_id uuid not null, parent_id uuid, is_deleted boolean not null default false, content text);
create table user_follows (id uuid primary key, follower_id uuid not null, following_id uuid not null);
SQL
sql service_party <<'SQL'
create table party_users (id uuid primary key, username varchar(50));
create table party_maps (id uuid primary key, owner_id uuid not null, friend_access varchar(10) not null default 'NONE');
create table party_groups (id uuid primary key, owner_id uuid not null);
create table party_group_members (group_id uuid not null, user_id uuid not null, role varchar(10) not null);
create table friendships (id uuid primary key, user_low_id uuid not null, user_high_id uuid not null, status varchar(10) not null);
create table map_user_shares (map_id uuid not null, user_id uuid not null, role varchar(10) not null);
create table map_group_shares (map_id uuid not null, group_id uuid not null);
create table pin_photos (id uuid primary key, object_key varchar(200) not null unique);
SQL

# ---- 고정 ID
id() { printf '%08d-0000-4000-8000-%012d' "$1" "$2"; }
U_USER=$(id 1 1); U_ADMIN=$(id 1 2); U_SUPER=$(id 1 3); U_DEL=$(id 1 9); U_GONE=$(id 1 8)   # U_GONE: IAM 에 행 자체가 없음
P1=$(id 2 1); P2=$(id 2 2); C1=$(id 3 1); S1=$(id 4 1); M1=$(id 5 1); M2=$(id 5 2); G1=$(id 6 1)

seed_services() {
  sql doro_auth <<SQL
truncate users;
insert into users values ('$U_USER','user@x','ACTIVE','USER'),('$U_ADMIN','admin@x','ACTIVE','ADMIN'),
  ('$U_SUPER','super@x','SUSPENDED','SUPER_ADMIN'),('$U_DEL','deleted@x','DELETED','USER');
SQL
  sql service_blog <<SQL
truncate blog_users, series, posts, comments, user_follows;
insert into blog_users values ('$U_USER','a'),('$U_ADMIN','b');
insert into posts values ('$P1','$U_USER',null);
insert into comments values ('$C1','$P1','$U_USER',null,false);
insert into series values ('$S1','$U_USER');
insert into user_follows values (gen_random_uuid(),'$U_USER','$U_ADMIN');
SQL
  sql service_party <<SQL
truncate pin_photos, party_users, party_maps, party_groups, party_group_members, friendships, map_user_shares, map_group_shares;
insert into party_users values ('$U_USER','a'),('$U_ADMIN','b');
insert into party_maps values ('$M1','$U_USER','VIEWER');
insert into party_groups values ('$G1','$U_USER');
insert into party_group_members values ('$G1','$U_USER','OWNER'),('$G1','$U_ADMIN','MEMBER');
insert into friendships values (gen_random_uuid(),'$U_USER','$U_ADMIN','ACCEPTED');
insert into map_user_shares values ('$M1','$U_ADMIN','EDITOR');
insert into map_group_shares values ('$M1','$G1');
SQL
}

# T <ns> <obj> <rel> <sns> <sid> [srel|-] [분 단위 나이(기본 60)]  — 튜플 한 개를 Guard 에 넣는다.
T() {
  local srel="'${6:-}'"; { [ -z "${6:-}" ] || [ "${6:-}" = - ]; } && srel=null
  sql doro_guard <<SQL
insert into relation_tuples (id, namespace, object_id, relation, subject_namespace, subject_id, subject_relation, created_at)
values (gen_random_uuid(), '$1', '$2', '$3', '$4', '$5', $srel, now() - interval '${7:-60} minutes');
SQL
}

seed_clean_tuples() {
  sql doro_guard <<<'truncate relation_tuples'
  # 블로그
  T blog_post "$P1" author user "$U_USER"
  T blog_comment "$C1" author user "$U_USER"; T blog_comment "$C1" post blog_post "$P1"
  T blog_series "$S1" owner user "$U_USER"
  T blog_user "$U_ADMIN" follower user "$U_USER"
  # 도로 파티
  T party_map "$M1" owner user "$U_USER"
  T party_map "$M1" viewer party_friends "$U_USER" friend
  T party_map "$M1" editor user "$U_ADMIN"
  T party_map "$M1" viewer party_group "$G1" member
  T party_group "$G1" owner user "$U_USER"; T party_group "$G1" member user "$U_ADMIN"
  T party_friends "$U_USER" friend user "$U_ADMIN"; T party_friends "$U_ADMIN" friend user "$U_USER"
  # IAM 역할
  T system doro admin user "$U_ADMIN"; T system doro super_admin user "$U_SUPER"
  T user "$U_USER" manager system doro admin;  T user "$U_USER" super_manager system doro super_admin
  T user "$U_ADMIN" manager system doro admin; T user "$U_ADMIN" super_manager system doro super_admin
  T user "$U_SUPER" super_manager system doro super_admin
  # 점검 대상이 아닌 네임스페이스(살아 있는 사용자 것)
  T game_room "$(id 7 1)" owner user "$U_USER"
}

tuple_count() { sql doro_guard <<<"select count(*) from relation_tuples"; }
tuple_hash() { sql doro_guard <<<"select coalesce(md5(string_agg(id::text, ',' order by id)), 'empty') from relation_tuples"; }
has() { sql doro_guard <<<"select count(*) from relation_tuples where $1"; }

# run <인자...>: 스크립트 실행. 출력은 $WORK/out, 종료 코드는 $CODE. 운영과 같은 방식(stdin 으로 스크립트 전달)으로 돌린다.
run() { set +e; bash -s -- "$@" < "$SCRIPT" > "$WORK/out" 2>&1; CODE=$?; set -e; }
cat_n() { grep -E "^\[$1\] " "$WORK/out" | sed -E 's/.*: ([0-9]+)$/\1/'; }

seed_services

echo "== 깨끗한 상태: 모두 0"
seed_clean_tuples
before="$(tuple_hash)"
run --report
eq "종료 코드 0" "$CODE" 0
for c in 1 2 3 4a 4b 4c; do eq "분류 $c = 0" "$(cat_n "$c")" 0; done
grep -q '어긋남 없음' "$WORK/out" && pass "'어긋남 없음' 출력" || fail "'어긋남 없음' 출력"
eq "보고 모드는 튜플을 바꾸지 않는다" "$(tuple_hash)" "$before"
grep -qE 'game_room' "$WORK/out" && pass "점검 대상이 아닌 네임스페이스는 개수만 참고로 나온다" || fail "미점검 네임스페이스 참고 출력"

echo "== 분류별 어긋남 감지"
# 1) 주체가 탈퇴/없는 사용자(블로그가 아닌 네임스페이스·party 도 포함)
T blog_post "$P1" author user "$U_DEL"
T game_room "$(id 7 2)" owner user "$U_DEL"
T party_group "$G1" member user "$U_GONE"
# 2) 객체가 사라짐(블로그 글/파티 모임/탈퇴 사용자 객체), 주체(글)가 사라짐
T blog_post "$(id 2 9)" author user "$U_USER"
T party_group "$(id 6 9)" member user "$U_USER"
T user "$U_DEL" manager system doro admin
T blog_comment "$C1" post blog_post "$(id 2 8)"
# 3) 객체는 있는데 튜플이 없음: 글 P2(작성자 U_USER), 지도 M2(소유자 U_ADMIN) 의 튜플이 없다
sql service_blog <<<"insert into posts values ('$P2','$U_USER',null)"
sql service_party <<<"insert into party_maps values ('$M2','$U_ADMIN','NONE')"
# 4a) IAM 역할로 설명되지 않는 system 튜플(권한 과다)  4b) ADMIN 의 멤버십 튜플 누락  4c) 불필요한 관리 튜플
T system doro admin user "$U_USER"
T system doro auditor user "$U_USER"
sql doro_guard <<<"delete from relation_tuples where namespace='system' and relation='admin' and subject_id='$U_ADMIN'"
T user "$U_USER" manager user "$U_ADMIN"
# 유예 시간 안의 고아(2분 전): 보고·삭제 대상이 아니다
T blog_post "$(id 2 7)" author user "$U_USER" - 2
# 점검 대상이 아닌 네임스페이스·비사용자 주체(삭제 계정이 아닌 것)
T game_room "$(id 7 3)" owner user "$U_USER"

run --report
eq "드리프트가 있으면 종료 코드 1" "$CODE" 1
eq "분류 1 = 3 (블로그 글 작성자 + game_room + party 멤버, 없는 사용자 포함)" "$(cat_n 1)" 3
eq "분류 2 = 4 (없는 글 객체, 없는 모임, 탈퇴 사용자 객체, 사라진 글 주체)" "$(cat_n 2)" 4
eq "분류 3 = 2 (글 P2 작성자, 지도 M2 소유자)" "$(cat_n 3)" 2
eq "분류 4a = 2 (system:doro#admin@USER, #auditor)" "$(cat_n 4a)" 2
eq "분류 4b = 1 (ADMIN 멤버십 누락)" "$(cat_n 4b)" 1
eq "분류 4c = 1 (user:U#manager@user)" "$(cat_n 4c)" 1
grep -q '경고: system 네임스페이스' "$WORK/out" && pass "권한 과다 튜플은 크게 경고한다" || fail "권한 과다 경고"
grep -q "$P2" "$WORK/out" && grep -q "$M2" "$WORK/out" && pass "샘플 ID(객체 ID)가 나온다" || fail "샘플 ID"
! grep -qE 'user@x|admin@x|deleted@x' "$WORK/out" && pass "이메일 등 개인정보는 출력되지 않는다" || fail "개인정보 출력"
grep -q '생성 10분 이내 1개' "$WORK/out" && pass "유예 시간 안 튜플은 제외하고 개수만 알린다" || fail "유예 개수 출력"

echo "== 보고 모드는 아무것도 쓰지 않는다"
before="$(tuple_hash)"; run --report; eq "해시 동일" "$(tuple_hash)" "$before"
[ ! -d "$BACKUP_DIR" ] && pass "백업 폴더도 만들지 않는다" || fail "보고 모드가 백업을 만들었다"

echo "== --max-delete 상한: 넘으면 아무것도 지우지 않고 중단"
run --fix-orphans --max-delete 3
[ "$CODE" != 0 ] && grep -q '상한' "$WORK/out" && pass "상한 초과 시 실패" || fail "상한 초과 처리 (code=$CODE)"
eq "상한 초과 시 튜플 그대로" "$(tuple_hash)" "$before"
[ ! -d "$BACKUP_DIR" ] && pass "상한 초과 시 백업도 만들지 않는다" || fail "상한 초과인데 백업 생성"

echo "== 잘못된 인자"
run --fix; eq "알 수 없는 인자는 종료 코드 2" "$CODE" 2
run --report --max-delete abc; eq "숫자가 아닌 상한은 종료 코드 2" "$CODE" 2

echo "== --fix-orphans: 1·2 분류만 지운다"
total_before="$(tuple_count)"
# 지우면 안 되는 것들의 개수를 미리 센다(분류 3·4 에 걸린 튜플, 미점검 네임스페이스, 유예 안 고아, 정상 튜플)
keep_sql="not (id in (select id from relation_tuples where
   (subject_namespace='user' and subject_id in ('$U_DEL','$U_GONE'))
or (namespace='blog_post' and object_id='$(id 2 9)') or (namespace='party_group' and object_id='$(id 6 9)')
or (namespace='user' and object_id='$U_DEL') or (namespace='blog_comment' and relation='post' and subject_id='$(id 2 8)')))"
kept_before="$(has "$keep_sql")"
run --fix-orphans
eq "종료 코드 1 (분류 3·4 는 남는다)" "$CODE" 1
eq "분류 1 = 0" "$(cat_n 1 | tail -1)" 0
eq "분류 2 = 0" "$(cat_n 2 | tail -1)" 0
eq "분류 3 은 그대로 2" "$(cat_n 3 | tail -1)" 2
eq "분류 4a 는 그대로 2" "$(cat_n 4a | tail -1)" 2
eq "분류 4b 는 그대로 1" "$(cat_n 4b | tail -1)" 1
eq "분류 4c 는 그대로 1" "$(cat_n 4c | tail -1)" 1
eq "정확히 7개만 지워졌다" "$(( total_before - $(tuple_count) ))" 7
eq "지우면 안 되는 튜플은 전부 남았다" "$(has "$keep_sql")" "$kept_before"
eq "권한 과다 튜플(system:doro#admin@USER)은 남았다" "$(has "namespace='system' and relation='admin' and subject_id='$U_USER'")" 1
eq "system:doro#auditor 도 남았다" "$(has "namespace='system' and relation='auditor'")" 1
eq "미점검 네임스페이스(살아 있는 사용자)는 남았다" "$(has "namespace='game_room' and subject_id='$U_USER'")" 2
eq "미점검 네임스페이스의 탈퇴 사용자 튜플은 분류 1 이라 지워졌다" "$(has "namespace='game_room' and subject_id='$U_DEL'")" 0
eq "유예 안 고아는 남았다" "$(has "namespace='blog_post' and object_id='$(id 2 7)'")" 1
eq "정상 튜플(글 작성자)은 남았다" "$(has "namespace='blog_post' and object_id='$P1' and subject_id='$U_USER'")" 1
bk="$(ls "$BACKUP_DIR"/guard-reconcile-doro_guard-*.sql.gz 2>/dev/null | head -1 || true)"
[ -n "$bk" ] && [ -s "$bk" ] && pass "pg_dump 백업이 만들어졌다" || fail "백업 없음"
[ -n "$bk" ] && gzip -dc "$bk" | grep -q 'relation_tuples' && pass "백업에 relation_tuples 가 들어 있다" || fail "백업 내용"

echo "== 멱등: 다시 돌려도 더 지우지 않는다"
h="$(tuple_hash)"; n_bk="$(ls "$BACKUP_DIR" | wc -l | tr -d ' ')"
run --fix-orphans
eq "튜플 그대로" "$(tuple_hash)" "$h"
grep -q '할 일 없음' "$WORK/out" && pass "'할 일 없음' 출력" || fail "'할 일 없음' 출력"
eq "삭제할 게 없으면 백업도 새로 만들지 않는다" "$(ls "$BACKUP_DIR" | wc -l | tr -d ' ')" "$n_bk"

echo "== 3·4 를 고친 뒤에는 깨끗해진다(고아 삭제가 정상 튜플을 건드리지 않았다는 확인)"
sql doro_guard <<<"delete from relation_tuples where namespace='system' and subject_id='$U_USER'; delete from relation_tuples where namespace='user' and subject_namespace='user'; delete from relation_tuples where object_id='$(id 2 7)'"
T blog_post "$P2" author user "$U_USER"; T party_map "$M2" owner user "$U_ADMIN"; T system doro admin user "$U_ADMIN"
run --report
eq "종료 코드 0" "$CODE" 0
for c in 1 2 3 4a 4b 4c; do eq "분류 $c = 0" "$(cat_n "$c")" 0; done

echo "== IAM 이 비어 있으면(잘못된 DB) 중단하고 아무것도 지우지 않는다"
sql doro_auth <<<"create table users_bak as select * from users; truncate users"
T blog_post "$(id 2 9)" author user "$U_USER"
h="$(tuple_hash)"; run --fix-orphans
[ "$CODE" != 0 ] && pass "실패 종료" || fail "IAM 비었는데 성공했다"
eq "튜플 그대로" "$(tuple_hash)" "$h"
sql doro_auth <<<"insert into users select * from users_bak; drop table users_bak"

echo "== --auto (cron 전용)"
mkdir -p "$WORK/bin"
cat > "$WORK/bin/fake-notify" <<'SH'
#!/usr/bin/env bash
cat >> "$FAKE_NOTIFY_LOG"
SH
cat > "$WORK/bin/fake-logger" <<'SH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_LOGGER_LOG"
SH
chmod +x "$WORK/bin/fake-notify" "$WORK/bin/fake-logger"
export FAKE_NOTIFY_LOG="$WORK/notify.log" FAKE_LOGGER_LOG="$WORK/logger.log" NOTIFY_CMD="$WORK/bin/fake-notify" LOGGER_BIN="$WORK/bin/fake-logger"
export AUTO_LOG="$WORK/auto/guard-reconcile.log"
rm -rf "$BACKUP_DIR"
auto_reset() { rm -f "$FAKE_NOTIFY_LOG" "$FAKE_LOGGER_LOG"; : > "$FAKE_NOTIFY_LOG"; : > "$FAKE_LOGGER_LOG"; }
lines() { wc -l < "$1" | tr -d ' '; }
auto_reset; seed_services; seed_clean_tuples

# A. 깨끗한 상태
run --auto
eq "깨끗하면 종료 코드 0" "$CODE" 0
eq "화면에는 요약 한 줄만 [$(tr "\n" "~" < "$WORK/out" | cut -c1-300)]" "$(lines "$WORK/out")" 1
grep -qE '^OK [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]+[+-][0-9]{4} c1=0 c2=0 c3=0 c4a=0 c4b=0 c4c=0 c5a=0 c5b=0 deleted=0 left=0$' "$WORK/out" && pass "요약 줄 형식(OK)" || fail "요약 줄 형식: $(cat "$WORK/out")"
eq "로그에 같은 한 줄이 덧붙었다" "$(lines "$AUTO_LOG")" 1
[ ! -d "$BACKUP_DIR" ] && pass "삭제가 없으면 백업도 없다" || fail "백업이 생겼다"
[ ! -s "$FAKE_NOTIFY_LOG" ] && pass "OK 는 알리지 않는다" || fail "OK 인데 알림"
grep -q 'user.info' "$FAKE_LOGGER_LOG" && pass "syslog 에는 info 로 남는다" || fail "syslog info"

# B. 고아 3개 + 보고만 하는 분류 3 한 건 → 지우고 WARN, 종료 코드 0
T blog_post "$(id 2 9)" author user "$U_USER"; T party_group "$(id 6 9)" member user "$U_USER"; T game_room "$(id 7 2)" owner user "$U_DEL"
sql service_blog <<<"insert into posts values ('$P2','$U_USER',null)"
run --auto
eq "WARN 이어도 종료 코드 0" "$CODE" 0
grep -qE '^WARN .* c1=1 c2=2 c3=1 c4a=0 c4b=0 c4c=0 c5a=0 c5b=0 deleted=3 left=1 backup=guard-reconcile-doro_guard-.*\.sql\.gz$' "$WORK/out" && pass "요약 줄(WARN, 분류별 개수, 삭제 3, 백업 이름)" || fail "WARN 요약: $(cat "$WORK/out")"
eq "고아 3개 삭제" "$(has "object_id in ('$(id 2 9)','$(id 6 9)') or subject_id='$U_DEL'")" 0
eq "분류 3 은 자동으로 지우거나 채우지 않는다(글 P2 튜플 없음)" "$(has "object_id='$P2'")" 0
eq "백업 1개" "$(ls "$BACKUP_DIR" | wc -l | tr -d ' ')" 1
[ ! -s "$FAKE_NOTIFY_LOG" ] && pass "WARN 은 알리지 않는다" || fail "WARN 인데 알림"
eq "로그 두 줄" "$(lines "$AUTO_LOG")" 2

# C. 상한 초과: 아무것도 지우지 않고 ABORT, 종료 코드 1, 알림
auto_reset
T blog_post "$(id 2 9)" author user "$U_USER"; T party_group "$(id 6 9)" member user "$U_USER"; T game_room "$(id 7 2)" owner user "$U_DEL"
h="$(tuple_hash)"; nb="$(ls "$BACKUP_DIR" | wc -l | tr -d ' ')"
AUTO_MAX_DELETE=2 run --auto
eq "종료 코드 1" "$CODE" 1
grep -qE '^ABORT .*상한 초과' "$WORK/out" && pass "ABORT + '상한 초과'" || fail "ABORT 요약: $(cat "$WORK/out")"
eq "튜플 그대로" "$(tuple_hash)" "$h"
eq "백업도 안 만든다" "$(ls "$BACKUP_DIR" | wc -l | tr -d ' ')" "$nb"
grep -q 'ABORT' "$FAKE_NOTIFY_LOG" && pass "텔레그램 알림 호출" || fail "ABORT 알림 없음"
grep -q 'user.err' "$FAKE_LOGGER_LOG" && pass "syslog 에 err 로 남는다" || fail "syslog err"
! grep -qE 'user@x|admin@x|deleted@x' "$FAKE_NOTIFY_LOG" "$AUTO_LOG" && pass "알림·로그에 개인정보 없음" || fail "개인정보"
run --auto --max-delete 2; eq "--max-delete 로 상한을 줄 수 있다(역시 ABORT)" "$CODE" 1

# D. 권한 과다 튜플(4a): 1·2 는 지우되 ALERT, 종료 코드 3, 4a 는 남는다
auto_reset
T system doro admin user "$U_USER"
run --auto
eq "ALERT 종료 코드 3" "$CODE" 3
grep -qE '^ALERT .* c4a=1 .*deleted=3 ' "$WORK/out" && pass "ALERT 줄이 맨 앞(고아는 지움)" || fail "ALERT 요약: $(cat "$WORK/out")"
eq "4a 튜플은 지우지 않았다" "$(has "namespace='system' and relation='admin' and subject_id='$U_USER'")" 1
grep -q 'ALERT' "$FAKE_NOTIFY_LOG" && pass "ALERT 알림 호출" || fail "ALERT 알림 없음"
auto_reset; run --auto
eq "다음 날에도 계속 ALERT(사람이 고칠 때까지)" "$CODE" 3
grep -q 'ALERT' "$FAKE_NOTIFY_LOG" && pass "계속 알린다" || fail "재알림"
sql doro_guard <<<"delete from relation_tuples where namespace='system' and subject_id='$U_USER'; delete from relation_tuples where object_id='$P2'"
sql service_blog <<<"delete from posts where id='$P2'"

# E. IAM 이 비었다 / DB 에 닿지 못한다 → ABORT
auto_reset
sql doro_auth <<<"create table users_bak as select * from users; truncate users"
T blog_post "$(id 2 9)" author user "$U_USER"; h="$(tuple_hash)"
run --auto
eq "IAM 비어 있으면 종료 코드 1" "$CODE" 1
grep -qE '^ABORT .*IAM' "$WORK/out" && pass "ABORT 사유에 IAM" || fail "IAM ABORT: $(cat "$WORK/out")"
eq "튜플 그대로" "$(tuple_hash)" "$h"
sql doro_auth <<<"insert into users select * from users_bak; drop table users_bak"
BLOG_DB=no_such_db run --auto
eq "서비스 DB 에 못 닿으면 종료 코드 1" "$CODE" 1
grep -qE '^ABORT .*no_such_db' "$WORK/out" && pass "ABORT 사유에 DB 이름" || fail "DB ABORT: $(cat "$WORK/out")"
eq "튜플 그대로" "$(tuple_hash)" "$h"
PG_CONTAINER=no-such-container run --auto
eq "컨테이너가 없으면 종료 코드 1" "$CODE" 1
grep -qE '^ABORT ' "$WORK/out" && pass "ABORT" || fail "컨테이너 ABORT"

# F. 백업은 최근 N개만 남긴다
rm -rf "$BACKUP_DIR"
for i in 1 2 3 4; do T blog_post "$(id 2 9)" author user "$U_USER"; KEEP_BACKUPS=2 run --auto; sleep 1; done
eq "백업은 최근 2개만" "$(ls "$BACKUP_DIR" | wc -l | tr -d ' ')" 2

# G. 로그 로테이션
rm -rf "$WORK/auto"
for i in 1 2 3 4 5; do AUTO_LOG_MAX_BYTES=200 run --auto; done
[ -f "$AUTO_LOG.1" ] && pass "로그가 상한을 넘으면 .1 로 교체된다" || fail "로테이션 안 됨"
[ "$(wc -c < "$AUTO_LOG" | tr -d ' ')" -le 500 ] && pass "현재 로그는 작게 유지된다" || fail "로그가 계속 커진다"

echo "== 분류 5: 스토리지 ↔ DB (보고 전용)"
auto_reset; seed_services; seed_clean_tuples
export MINIO_CONTAINER="$CT"      # 가짜 MinIO: 같은 컨테이너의 /data 에 만든 디렉터리 트리(객체 = xl.meta 가 든 디렉터리)
U() { printf '%08d-1111-4111-8111-%012d' "$1" "$2"; }
obj() { # <경로> <old|new> [part]  — 객체 디렉터리 하나(old = 2020년, new = 지금)
  docker exec "$CT" sh -c "mkdir -p '/data/$1' && touch '/data/$1/xl.meta' && { [ '${3:-}' != part ] || { mkdir -p '/data/$1/part-dir' && touch '/data/$1/part-dir/part.1'; }; } && { [ '$2' = new ] || touch -d '2020-01-01 00:00:00' '/data/$1/xl.meta'; }"
}
storage_count() { docker exec "$CT" sh -c "cd /data && find . -type f | wc -l" | tr -d ' '; }
BK=doro-blog-media; PK=doro-party-media
B_POST=$(U 1 1); B_CMT=$(U 1 2); B_PROF=$(U 1 3); B_ORPHAN=$(U 1 4); B_NEW=$(U 1 5); B_SER=$(U 1 6); B_ABOUT=$(U 1 7); B_BIG=$(U 1 8); B_THUMB=$(U 1 9); B_DESC=$(U 2 1)
sql service_blog <<SQL
update posts set content = 'hello ![x](/media/posts/2026/09/$B_POST.png) bye', thumbnail_url = '/media/posts/2026/09/$B_THUMB.png' where id = '$P1';
update comments set content = 'img /media/posts/2026/09/$B_CMT.png' where id = '$C1';
update blog_users set profile_image_url = 'https://x/media/posts/2026/09/$B_PROF.png', about_markdown = '![](/media/posts/2026/09/$B_ABOUT.png)' where id = '$U_USER';
update series set thumbnail_url = '/media/posts/2026/09/$B_SER.png', description = 'see /media/posts/2026/09/$B_DESC.png' where id = '$S1';
SQL
for u in $B_POST $B_CMT $B_PROF $B_SER $B_ABOUT $B_THUMB $B_DESC; do obj "$BK/posts/2026/09/$u.png" old; done
obj "$BK/posts/2026/09/$B_ORPHAN.png" old                 # 어디에서도 참조하지 않는 오래된 객체 -> 5a
obj "$BK/posts/2026/09/$B_NEW.png" new                    # 참조 없지만 방금 올라옴 -> 유예로 제외
obj "$BK/posts/2026/10/$B_BIG.png" old part              # 큰 객체(part.1 포함) 참조 없음 -> 5a 한 건
PHOTO1=$(U 3 1); PHOTO2=$(U 3 2); PHOTO3=$(U 3 3); PHOTO4=$(U 3 4); PHOTO5=$(U 3 5); PIN=$(U 4 1)
sql service_party <<SQL
insert into pin_photos values ('$PHOTO1', 'pins/$PIN/$PHOTO1.jpg'), ('$PHOTO2', 'pins/$PIN/$PHOTO2.jpg'), ('$PHOTO5', 'pins/$PIN/$PHOTO5.jpg');
SQL
obj "$PK/pins/$PIN/$PHOTO1.jpg" old; obj "$PK/pins/$PIN/$PHOTO2.jpg" old
obj "$PK/pins/$PIN/$PHOTO3.jpg" old                       # DB 에 없는 오래된 객체 -> 5a
obj "$PK/pins/$PIN/$PHOTO4.jpg" new                       # DB 에 없지만 방금 -> 유예
                                                          # PHOTO5 는 DB 에만 있다 -> 5b
obj "other-bucket/x/$(U 9 9).png" old                     # 점검 대상이 아닌 버킷은 무시
n_files="$(storage_count)"; h="$(tuple_hash)"

run --report
eq "5a = 3 (블로그 미참조 2[작은 객체, part 있는 큰 객체] + 파티 DB 에 없음 1)" "$(cat_n 5a)" 3
eq "5b = 1 (DB 에만 있는 사진)" "$(cat_n 5b)" 1
eq "참조된 7개(본문·썸네일·댓글·프로필·소개·시리즈 썸네일·설명)는 5a 에 안 나온다" "$(grep -cE "$B_POST|$B_CMT|$B_PROF|$B_SER|$B_ABOUT|$B_THUMB|$B_DESC" "$WORK/out")" 0
grep -q "$B_ORPHAN" "$WORK/out" && grep -q "$B_BIG" "$WORK/out" && grep -q "$PHOTO3" "$WORK/out" && grep -q "$PHOTO5" "$WORK/out" && pass "샘플 키가 나온다" || fail "샘플 키"
! grep -qE "$B_NEW|$PHOTO4|other-bucket" "$WORK/out" && pass "유예 안 객체와 다른 버킷은 제외" || fail "유예/다른 버킷이 보고됨"
grep -q '보고 전용' "$WORK/out" && pass "보고 전용이라고 표시한다" || fail "보고 전용 표시"
eq "종료 코드 1 (깨진 사진 5b 가 남아 있다)" "$CODE" 1
eq "스토리지 파일은 그대로" "$(storage_count)" "$n_files"
run --report --storage-grace-minutes 0
eq "유예 0 이면 방금 올린 두 객체도 보인다(5a = 5)" "$(cat_n 5a)" 5

echo "== 5 는 --fix-orphans/--auto 도 건드리지 않는다"
T blog_post "$(id 2 9)" author user "$U_USER"      # 분류 2 하나(지워져야 한다)
run --fix-orphans
eq "고아(분류 2)는 지웠다" "$(has "object_id='$(id 2 9)'")" 0
eq "스토리지 파일은 그대로(--fix-orphans)" "$(storage_count)" "$n_files"
grep -q "5a\] .*: 3" "$WORK/out" && pass "5a 는 그대로 3" || fail "5a 변동"
T blog_post "$(id 2 9)" author user "$U_USER"
run --auto
grep -qE '^WARN .* c5a=3 c5b=1 deleted=1 left=1 ' "$WORK/out" && pass "--auto 요약에 c5a/c5b, 5b 는 WARN 으로 남는다" || fail "auto 요약: $(cat "$WORK/out")"
eq "종료 코드 0(WARN)" "$CODE" 0
eq "스토리지 파일은 그대로(--auto)" "$(storage_count)" "$n_files"
eq "DB 쪽 사진 행도 그대로" "$(sql service_party <<<'select count(*) from pin_photos')" 3

echo "== 스토리지를 읽지 못하면 분류 5 만 건너뛴다"
MINIO_CONTAINER=no-such-minio run --report
grep -q '분류 5 를 건너뛴다' "$WORK/out" && pass "건너뛴다고 알린다" || fail "건너뜀 알림"
eq "5a = 0, 5b = 0 (오탐 없음)" "$(cat_n 5a)$(cat_n 5b)" "00"
eq "나머지 분류는 정상 동작(종료 코드 0)" "$CODE" 0
MINIO_CONTAINER=no-such-minio run --auto
grep -qE '^OK .* c5a=0 c5b=0 deleted=0 left=0$' "$WORK/out" && pass "--auto 도 중단 없이 OK" || fail "auto 건너뜀: $(cat "$WORK/out")"
unset MINIO_CONTAINER
docker exec "$CT" rm -rf /data

echo "== 텔레그램 스크립트 탐색 / --heartbeat"
# 탐색: NOTIFY_CMD 가 없으면 이 스크립트 옆의 notify-telegram.sh 를 쓴다(예전에는 ~/doro/scripts 만 봐서 알림이 안 나갔다)
HB="$WORK/hb"; rm -rf "$HB"; mkdir -p "$HB/ops" "$HB/home"
cp "$SCRIPT" "$HB/ops/guard-reconcile.sh"
cat > "$HB/ops/notify-telegram.sh" <<'SH'
#!/usr/bin/env bash
cat >> "$HB_NOTIFY_LOG"
SH
chmod +x "$HB/ops/notify-telegram.sh"
hb() { set +e; env -u NOTIFY_CMD HOME="$HB/home" HB_NOTIFY_LOG="$HB/notify.log" LOGGER_BIN="$WORK/bin/fake-logger" AUTO_LOG="$HB/auto.log" bash "$HB/ops/guard-reconcile.sh" --heartbeat > "$HB/out" 2>&1; CODE=$?; set -e; }
: > "$HB/notify.log"; rm -f "$HB/auto.log"
hb
eq "로그가 아예 없으면 종료 코드 3" "$CODE" 3
grep -q 'HEARTBEAT' "$HB/notify.log" && pass "스크립트 옆의 notify-telegram.sh 로 알렸다(NOTIFY_CMD 없이)" || fail "heartbeat 알림 없음: $(cat "$HB/out")"
: > "$HB/notify.log"; echo "OK 2026-10-21T04:30:02+0900 c1=0" > "$HB/auto.log"
hb
eq "로그가 방금 갱신됐으면 종료 코드 0" "$CODE" 0
[ ! -s "$HB/notify.log" ] && [ ! -s "$HB/out" ] && pass "정상이면 아무것도 알리지 않고 조용하다" || fail "정상인데 알림/출력"
touch -t 202001010000 "$HB/auto.log"
hb
eq "로그가 오래됐으면 종료 코드 3" "$CODE" 3
grep -q '30시간 넘게' "$HB/notify.log" && pass "오래됐다고 알린다" || fail "오래된 로그 알림 없음"

echo
if [ "$FAILURES" -eq 0 ]; then echo "모두 통과"; else echo "실패 $FAILURES 건"; exit 1; fi
