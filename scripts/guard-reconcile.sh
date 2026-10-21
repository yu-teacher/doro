#!/usr/bin/env bash
# Guard 튜플 정합성 점검: Guard(relation_tuples)와 IAM·각 서비스 DB 를 맞대어 어긋남(drift)을 분류별로 보고한다.
# 운영 서버(mini)에서 실행한다. 비밀 값은 다루지 않고, 보고서에는 ID 만 나온다(이메일·닉네임 등 개인정보 없음).
#
#   ssh mini 'bash -s -- --report'       < scripts/guard-reconcile.sh   # 읽기 전용(기본): 분류별 개수와 샘플 ID
#   ssh mini 'bash -s -- --fix-orphans'  < scripts/guard-reconcile.sh   # 1·2 분류만 삭제(먼저 doro_guard pg_dump 백업)
#   ~/ops/guard-reconcile.sh --auto                                       # cron 전용: --fix-orphans --max-delete 500 + 한 줄 요약 로그(아래)
#
# 분류
#   1. 주체가 IAM 에서 DELETED/없는 사용자인 튜플                       (삭제 대상)
#   2. 객체(또는 그룹 주체)가 서비스 DB 에서 사라진 튜플                (삭제 대상)
#   3. 서비스 DB 에 객체가 있는데 소유자/작성자 튜플이 없음              (권한 상실 위험, 보고만)
#   4. IAM 역할(users.role)과 system:doro·user 튜플의 불일치             (보고만, 권한이 늘어난 쪽은 크게 경고)
#   5. 스토리지(MinIO) ↔ DB 대조 — 보고 전용, 어떤 모드에서도 지우지 않는다(이미지 삭제는 되돌릴 수 없고, 블로그는 편집 중 뺀 이미지를 undo 용으로 일부러 남긴다)
#      5a 스토리지에 있는데 DB 가 쓰지 않는 객체: 블로그 = 본문·썸네일·시리즈·댓글·프로필 어디에도 파일명(uuid)이 없음(MediaCleanup.isReferenced 와 같은 기준), 파티 = pin_photos.object_key 에 없음
#      5b DB(pin_photos)에는 있는데 스토리지에 없는 행(깨진 사진)
#      MinIO 컨테이너에 닿지 못하면 이 분류만 건너뛴다(1~4 와 --auto 삭제에는 영향 없음). 5a 는 파일 수정 시각이 --storage-grace-minutes(기본 60) 안이면 제외한다(업로드 후 글 저장 전일 수 있음).
# --fix-orphans 는 1·2 만 지운다. 3·4 는 어떤 경우에도 건드리지 않는다. 몇 번 돌려도 결과가 같다(멱등).
#
# 안전장치: ① 생성 직후 튜플은 건드리지 않는다(서비스가 DB 커밋 전에 튜플을 먼저 쓴다) --grace-minutes N(기본 10).
#           ② 한 번에 지울 수 있는 개수 상한 --max-delete N(기본 2000). 넘으면 아무것도 지우지 않고 중단한다.
#           ③ 삭제는 한 트랜잭션. 조회 DB 가 하나라도 읽히지 않으면 중단한다.
# 종료 코드: 0 = 어긋남 없음, 1 = 어긋남이 남아 있음(보고/삭제 후 기준), 2 = 사용법 오류, 그 외 = 실행 실패.
#
# --auto (cron 전용, scripts/install-guard-reconcile-cron.sh 가 매일 새벽에 건다)
#   --fix-orphans 와 같되 상한 기본 500, 화면 출력 없이 한 줄 요약만 $AUTO_LOG(기본 ~/backups/auto/guard-reconcile.log)에 덧붙인다.
#   한 줄 형식: <상태> <시각> c1=.. c2=.. c3=.. c4a=.. c4b=.. c4c=.. deleted=N left=N [backup=파일]  또는  ABORT <시각> <사유>
#   상태 OK(전부 0) / WARN(보고만 하는 3·4b·4c 만 남음) / ALERT(4a: 권한 과다 튜플 있음, 삭제는 1·2 만 하고 종료 코드 3) / ABORT(아무것도 안 지우고 종료 코드 1)
#   ABORT 사유: 상한 초과(대량 이상 징후), IAM 이 비었음, DB 에 닿지 못함, 예기치 않은 실패. 3·4 는 절대 자동으로 지우지 않는다.
#   ALERT/ABORT 는 syslog(태그 doro-guard-reconcile, -> journal -> Loki)와 텔레그램(notify-telegram.sh 가 있으면)으로도 알린다. 이 알림에는 개수만 담긴다.
#   --heartbeat: 로그가 30시간(HEARTBEAT_MAX_HOURS) 넘게 안 갱신됐으면 같은 경로로 알린다(cron 중단·서버 정지 감지, 종료 코드 3). 설치 스크립트가 매일 08:30 에 건다.
#   백업은 삭제가 있을 때만 만들고 최근 14개만 남긴다. 로그는 1MB 를 넘으면 .1 로 바꾼다.
set -Eeuo pipefail

STORAGE_GRACE="${STORAGE_GRACE_MINUTES:-60}"
MODE=report; AUTO=false; HEARTBEAT=false; MAX_DELETE="${MAX_DELETE:-}"; GRACE_MIN="${GRACE_MINUTES:-10}"
while [ $# -gt 0 ]; do
  case "$1" in
    --report) MODE=report ;;
    --fix-orphans) MODE=fix ;;
    --auto) MODE=fix; AUTO=true ;;
    --heartbeat) HEARTBEAT=true ;;
    --max-delete) MAX_DELETE="${2:-}"; shift ;;
    --grace-minutes) GRACE_MIN="${2:-}"; shift ;;
    --storage-grace-minutes) STORAGE_GRACE="${2:-}"; shift ;;
    *) echo "사용법: bash -s -- --report | --fix-orphans | --auto [--max-delete N] [--grace-minutes N] | --heartbeat" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$MAX_DELETE" ] || { if [ "$AUTO" = true ]; then MAX_DELETE="${AUTO_MAX_DELETE:-500}"; else MAX_DELETE=2000; fi; }
case "$MAX_DELETE" in ''|*[!0-9]*) echo "--max-delete 는 숫자여야 한다" >&2; exit 2 ;; esac
case "$GRACE_MIN" in ''|*[!0-9]*) echo "--grace-minutes 는 숫자여야 한다" >&2; exit 2 ;; esac
case "$STORAGE_GRACE" in ''|*[!0-9]*) echo "--storage-grace-minutes 는 숫자여야 한다" >&2; exit 2 ;; esac

PG="${PG_CONTAINER:-doro-postgres}"
PGUSER_NAME="${PG_USER:-doro_admin}"
GUARD_DB="${GUARD_DB:-doro_guard}"
IAM_DB="${IAM_DB:-doro_auth}"
BLOG_DB="${BLOG_DB:-service_blog}"
PARTY_DB="${PARTY_DB:-service_party}"
MINIO="${MINIO_CONTAINER:-doro-minio}"
MINIO_DATA="${MINIO_DATA:-/data}"
BLOG_BUCKET="${BLOG_BUCKET:-doro-blog-media}"
PARTY_BUCKET="${PARTY_BUCKET:-doro-party-media}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/guard-reconcile}"
KEEP_BACKUPS="${KEEP_BACKUPS:-14}"
AUTO_LOG="${AUTO_LOG:-$HOME/backups/auto/guard-reconcile.log}"
AUTO_LOG_MAX_BYTES="${AUTO_LOG_MAX_BYTES:-1048576}"
LOGGER_BIN="${LOGGER_BIN:-logger}"
NOTIFY_CMD="${NOTIFY_CMD:-}"
# 텔레그램 스크립트는 이 스크립트 옆(~/ops, 설치 스크립트가 같이 복사한다) → 옛 ~/doro/scripts 순으로 찾는다.
# (예전에는 ~/doro/scripts 만 봐서, 그 폴더가 비어 있는 서버에서는 ALERT/ABORT 텔레그램 알림이 한 번도 나가지 않았다)
if [ -z "$NOTIFY_CMD" ]; then
  for _c in "$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)/notify-telegram.sh" "$HOME/ops/notify-telegram.sh" "$HOME/doro/scripts/notify-telegram.sh"; do
    [ -x "$_c" ] && { NOTIFY_CMD="$_c"; break; }
  done
fi
HEARTBEAT_MAX_HOURS="${HEARTBEAT_MAX_HOURS:-30}"
# --heartbeat: 점검 로그가 오래 갱신되지 않았으면(cron 이 안 돌거나 서버가 꺼져 있었음) 텔레그램·syslog 로 알린다. 점검 자체는 하지 않는다.
if [ "$HEARTBEAT" = true ]; then
  if [ ! -f "$AUTO_LOG" ] || [ -n "$(find "$AUTO_LOG" -mmin +"$((HEARTBEAT_MAX_HOURS * 60))" 2>/dev/null)" ]; then
    line="HEARTBEAT $(date +%FT%T%z) Guard 점검 로그가 ${HEARTBEAT_MAX_HOURS}시간 넘게 갱신되지 않았다(cron 중단 의심): $AUTO_LOG"
    "$LOGGER_BIN" -t doro-guard-reconcile -p user.err -- "$line" >/dev/null 2>&1 || true
    [ -z "$NOTIFY_CMD" ] || printf '[Doro Guard 점검] %s\n' "$line" | "$NOTIFY_CMD" >/dev/null 2>&1 || true
    echo "$line"; exit 3
  fi
  exit 0
fi
[ "$AUTO" = false ] || PATH="$PATH:/usr/local/bin:/usr/bin:/bin"   # cron 의 좁은 PATH 에서도 docker 를 찾는다

exec 3>&1
WORK="$(mktemp -d)"; chmod 700 "$WORK"
SUMMARY_DONE=false
# --auto 에서는 화면에 아무것도 내지 않고 상세 출력은 버린다(요약 한 줄만 남긴다). 수동 실행은 stderr 로 낸다.
log() { if [ "$AUTO" = true ]; then printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >> "$WORK/detail"; else printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; fi; }
lasterr() { grep -m1 -E 'ERROR|FATAL' "$WORK/err" 2>/dev/null | cut -c1-160 || true; }

# 요약 한 줄을 로그(로테이션 포함)·화면·syslog 에 남기고, ALERT/ABORT 는 텔레그램으로도 알린다. 개수·ID 외의 값은 담지 않는다.
auto_summary() { # <상태> <본문>
  local line size
  line="$1 $(date +%FT%T%z) $2"
  mkdir -p "$(dirname "$AUTO_LOG")"
  size=0; [ ! -f "$AUTO_LOG" ] || size="$(wc -c < "$AUTO_LOG" | tr -d ' ')"
  if [ "${size:-0}" -gt "$AUTO_LOG_MAX_BYTES" ]; then mv -f "$AUTO_LOG" "$AUTO_LOG.1"; fi
  printf '%s\n' "$line" >> "$AUTO_LOG"
  printf '%s\n' "$line" >&3   # emit 의 { } > 파일 리다이렉트 안에서 불려도 화면(원래 stdout)으로 나가게 fd 3 을 쓴다
  SUMMARY_DONE=true
  case "$1" in
    ALERT|ABORT) "$LOGGER_BIN" -t doro-guard-reconcile -p user.err -- "$line" >/dev/null 2>&1 || true
                 if [ -n "$NOTIFY_CMD" ]; then printf '[Doro Guard 점검] %s\n' "$line" | "$NOTIFY_CMD" >/dev/null 2>&1 || true; fi ;;
    *)           "$LOGGER_BIN" -t doro-guard-reconcile -p user.info -- "$line" >/dev/null 2>&1 || true ;;
  esac
}
die() {
  log "ERROR: $*"
  if [ "$AUTO" = true ]; then auto_summary ABORT "$*"; else [ ! -s "$WORK/err" ] || cat "$WORK/err" >&2; fi
  exit 1
}
psqlc() { docker exec -i "$PG" psql -U "$PGUSER_NAME" -v ON_ERROR_STOP=1 -q -tA -F '|' "$@"; }

docker inspect "$PG" >/dev/null 2>&1 || die "컨테이너 $PG 가 없다"

trap 'rc=$?; if [ "$AUTO" = true ] && [ "$SUMMARY_DONE" = false ] && [ "$rc" -ne 0 ]; then auto_summary ABORT "예기치 않은 실패(exit $rc)"; fi; rm -rf "$WORK"' EXIT

# 다른 DB 의 값을 doro_guard 세션의 임시 테이블로 옮기는 SQL 을 만든다(COPY ... FROM stdin 인라인 데이터).
# 읽는 컬럼은 ID·역할·상태·시각뿐이다.
emit() { # <임시테이블> <컬럼정의> <DB> <SELECT>
  printf 'create temp table %s (%s);\ncopy %s from stdin;\n' "$1" "$2" "$1"
  psqlc -d "$3" -c "copy ($4) to stdout" </dev/null 2>>"$WORK/err" || die "$3 조회 실패($1): $(lasterr)"
  printf '\\.\n'
}

# MinIO(파일시스템 백엔드) 데이터 디렉터리를 읽기 전용으로 나열한다: 객체 하나 = xl.meta 가 든 디렉터리. 컨테이너에 find 가 없어 sh 루프만 쓴다.
# 자격증명·mc 는 쓰지 않는다. 컨테이너가 없거나 나열에 실패하면 이 분류만 건너뛴다(storage_ok = false).
STORAGE_LIST_SH='cd "$0" || exit 1
walk() { for e in "$1"/*; do [ -d "$e" ] || continue; if [ -f "$e/xl.meta" ]; then printf "%s\t%s\n" "$e" "$(stat -c %Y "$e/xl.meta")"; else walk "$e"; fi; done; }
for b in *; do [ -d "$b" ] && walk "$b"; done
printf "NOW\t%s\n" "$(date +%s)"'
emit_storage() {
  local out
  printf 'create temp table storage_ok (v boolean);\ncreate temp table storage_now (e bigint);\ncreate temp table minio_objects (path text, mtime bigint);\n'
  if docker inspect "$MINIO" >/dev/null 2>&1 \
     && out="$(docker exec -i "$MINIO" sh -c "$STORAGE_LIST_SH" "$MINIO_DATA" </dev/null 2>>"$WORK/err")" \
     && printf '%s\n' "$out" | grep -q '^NOW'"$(printf '\t')"; then
    printf "insert into storage_ok values (true);\ninsert into storage_now values (%s);\ncopy minio_objects from stdin;\n" "$(printf '%s\n' "$out" | awk -F'\t' '$1=="NOW"{print $2}')"
    printf '%s\n' "$out" | awk -F'\t' '$1!="NOW"'
    printf '\\.\n'
  else
    log "스토리지($MINIO)를 읽지 못해 분류 5 를 건너뛴다"
    printf 'insert into storage_ok values (false);\ninsert into storage_now values (0);\n'
  fi
}

build_load() {
  {
    emit iam_users 'id text, role text, status text' "$IAM_DB" "select id::text, role, status from users"
    emit blog_users_t 'id text' "$BLOG_DB" "select id::text from blog_users"
    emit blog_posts 'id text, user_id text' "$BLOG_DB" "select id::text, user_id::text from posts"
    emit blog_comments 'id text, post_id text, user_id text' "$BLOG_DB" "select id::text, post_id::text, user_id::text from comments"
    emit blog_series 'id text, user_id text' "$BLOG_DB" "select id::text, user_id::text from series"
    emit blog_follows 'follower_id text, following_id text' "$BLOG_DB" "select follower_id::text, following_id::text from user_follows"
    emit party_users_t 'id text' "$PARTY_DB" "select id::text from party_users"
    emit party_maps_t 'id text, owner_id text, friend_access text' "$PARTY_DB" "select id::text, owner_id::text, friend_access from party_maps"
    emit party_groups_t 'id text' "$PARTY_DB" "select id::text from party_groups"
    emit party_group_members_t 'group_id text, user_id text, role text' "$PARTY_DB" "select group_id::text, user_id::text, role from party_group_members"
    emit party_friendships_t 'low_id text, high_id text' "$PARTY_DB" "select user_low_id::text, user_high_id::text from friendships where status = 'ACCEPTED'"
    emit party_map_user_shares_t 'map_id text, user_id text, role text' "$PARTY_DB" "select map_id::text, user_id::text, role from map_user_shares"
    emit party_map_group_shares_t 'map_id text, group_id text' "$PARTY_DB" "select map_id::text, group_id::text from map_group_shares"
    # 5: DB 쪽을 먼저 읽고 스토리지를 마지막에 나열한다(그 사이 올라온 파일이 5b 오탐이 되지 않게). 블로그는 본문 전체가 아니라 거기 들어 있는 uuid 만 옮긴다.
    emit party_photo_keys 'object_key text' "$PARTY_DB" "select object_key from pin_photos"
    emit blog_ref_uuids 'u text' "$BLOG_DB" "select distinct m[1] from (
        select content t from posts union all select thumbnail_url from posts
        union all select thumbnail_url from series union all select description from series
        union all select content from comments
        union all select profile_image_url from blog_users union all select about_markdown from blog_users) x,
      regexp_matches(x.t, '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})', 'g') m"
    emit_storage
  } > "$WORK/load.sql"
}

# 분류 SQL. 위 임시 테이블과 relation_tuples 만 읽는다.
write_classify() {
  cat > "$WORK/classify.sql" <<'SQL'
create temp table params as select :grace::int as grace, :maxdel::bigint as maxdel, :sgrace::int as sgrace,
  :'blog_bucket'::text as blog_bucket, :'party_bucket'::text as party_bucket;
create temp table iam_live as select id from iam_users where status <> 'DELETED';
create temp table iam_gone as select id from iam_users where status = 'DELETED';
do $$ begin
  if (select count(*) from iam_users) = 0 then raise exception 'IAM users 가 비어 있다. 잘못된 DB 로 보여 중단'; end if;
end $$;

-- 존재하는 객체. blog_user/party_friends/user 의 객체 ID 는 IAM 사용자 ID 라서 IAM 에서 탈퇴한 사용자는 뺀다.
create temp table objects (ns text, id text);
insert into objects select 'blog_post', id from blog_posts;
insert into objects select 'blog_comment', id from blog_comments;
insert into objects select 'blog_series', id from blog_series;
insert into objects select 'blog_user', id from blog_users_t where id not in (select id from iam_gone);
insert into objects select 'party_map', id from party_maps_t;
insert into objects select 'party_group', id from party_groups_t;
insert into objects select 'party_friends', id from party_users_t where id not in (select id from iam_gone);
insert into objects select 'user', id from iam_live;
create index on objects (ns, id);
create temp table tracked as select distinct ns from objects;

create temp table drift (cat text, sub text, tuple_id uuid, ns text, rel text, key text, loud boolean not null default false);

-- 1. 주체가 삭제/없는 IAM 사용자
insert into drift
select '1', 'subject-deleted', t.id, t.namespace, t.relation, t.subject_id, false
from relation_tuples t
where t.subject_namespace = 'user'
  and t.subject_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  and t.created_at < now() - (:grace * interval '1 minute')
  and (t.subject_id in (select id from iam_gone) or t.subject_id not in (select id from iam_users));

-- 2. 객체가 사라진 튜플(객체 쪽), 그리고 사라진 그룹·글 등을 주체로 하는 튜플(주체 쪽). 사용자 주체는 1번이 맡는다.
insert into drift
select '2',
       case when obj_missing then 'object-missing' else 'subject-missing' end,
       id, namespace, relation,
       case when obj_missing then object_id else subject_id end, false
from (
  select t.id, t.namespace, t.relation, t.object_id, t.subject_id,
         (t.namespace in (select ns from tracked)
          and t.object_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and not exists (select 1 from objects o where o.ns = t.namespace and o.id = t.object_id)) as obj_missing,
         (t.subject_namespace <> 'user' and t.subject_namespace in (select ns from tracked)
          and t.subject_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and not exists (select 1 from objects o where o.ns = t.subject_namespace and o.id = t.subject_id)) as subj_missing
  from relation_tuples t
  where t.created_at < now() - (:grace * interval '1 minute')
    and t.id not in (select tuple_id from drift)
) x
where obj_missing or subj_missing;

-- 서비스가 가져야 하는 튜플(소유자/작성자/멤버십). 탈퇴한 사용자가 주체인 기대 튜플은 만들지 않는다(서비스가 일부러 지운다).
create temp table expected (cat text, ns text, obj text, rel text, sns text, sid text, srel text, key text);
-- 블로그
insert into expected select '3', 'blog_post', p.id, 'author', 'user', p.user_id, null, p.id from blog_posts p where p.user_id in (select id from iam_live);
insert into expected select '3', 'blog_comment', c.id, 'author', 'user', c.user_id, null, c.id from blog_comments c where c.user_id in (select id from iam_live);
insert into expected select '3', 'blog_comment', c.id, 'post', 'blog_post', c.post_id, null, c.id from blog_comments c;
insert into expected select '3', 'blog_series', s.id, 'owner', 'user', s.user_id, null, s.id from blog_series s where s.user_id in (select id from iam_live);
insert into expected select '3', 'blog_user', f.following_id, 'follower', 'user', f.follower_id, null, f.following_id
  from blog_follows f where f.follower_id in (select id from iam_live) and f.following_id in (select id from iam_live);
-- 도로 파티
insert into expected select '3', 'party_map', m.id, 'owner', 'user', m.owner_id, null, m.id from party_maps_t m where m.owner_id in (select id from iam_live);
insert into expected select '3', 'party_map', m.id, lower(m.friend_access), 'party_friends', m.owner_id, 'friend', m.id
  from party_maps_t m where m.friend_access in ('VIEWER', 'EDITOR') and m.owner_id in (select id from iam_live);
insert into expected select '3', 'party_map', s.map_id, lower(s.role), 'user', s.user_id, null, s.map_id from party_map_user_shares_t s where s.user_id in (select id from iam_live);
insert into expected select '3', 'party_map', s.map_id, 'viewer', 'party_group', s.group_id, 'member', s.map_id from party_map_group_shares_t s;
insert into expected select '3', 'party_group', g.group_id, lower(g.role), 'user', g.user_id, null, g.group_id from party_group_members_t g where g.user_id in (select id from iam_live);
insert into expected select '3', 'party_friends', f.low_id, 'friend', 'user', f.high_id, null, f.low_id
  from party_friendships_t f where f.low_id in (select id from iam_live) and f.high_id in (select id from iam_live);
insert into expected select '3', 'party_friends', f.high_id, 'friend', 'user', f.low_id, null, f.high_id
  from party_friendships_t f where f.low_id in (select id from iam_live) and f.high_id in (select id from iam_live);
-- IAM 역할(UserRelationSyncService.tuplesFor 와 같은 규칙). 탈퇴하지 않은 모든 계정 대상.
insert into expected select '4b', 'system', 'doro', 'super_admin', 'user', id, null, id from iam_users where status <> 'DELETED' and role = 'SUPER_ADMIN';
insert into expected select '4b', 'system', 'doro', 'admin', 'user', id, null, id from iam_users where status <> 'DELETED' and role = 'ADMIN';
insert into expected select '4c', 'user', id, 'manager', 'system', 'doro', 'admin', id from iam_users where status <> 'DELETED' and role in ('ADMIN', 'USER');
insert into expected select '4c', 'user', id, 'super_manager', 'system', 'doro', 'super_admin', id from iam_users where status <> 'DELETED' and role in ('ADMIN', 'USER', 'SUPER_ADMIN');

-- 3 / 4b / 4c: 있어야 하는데 없다
insert into drift
select e.cat, case e.cat when '3' then 'owner-tuple-missing' when '4b' then 'role-tuple-missing' else 'manager-tuple-missing' end,
       null, e.ns, e.rel, e.key, e.cat = '4b'
from expected e
where not exists (
  select 1 from relation_tuples t
  where t.namespace = e.ns and t.object_id = e.obj and t.relation = e.rel
    and t.subject_namespace = e.sns and t.subject_id = e.sid and t.subject_relation is not distinct from e.srel);

-- 4a: system 네임스페이스에 IAM 역할로 설명되지 않는 튜플 = 권한이 늘어난 쪽(크게 경고)
insert into drift
select '4a', 'unexpected-system-tuple', t.id, t.namespace, t.relation, coalesce(nullif(t.subject_id, ''), t.object_id), true
from relation_tuples t
where t.namespace = 'system' and t.id not in (select tuple_id from drift where tuple_id is not null)
  and not exists (select 1 from expected e where e.cat = '4b' and e.ns = t.namespace and e.obj = t.object_id and e.rel = t.relation
                    and e.sns = t.subject_namespace and e.sid = t.subject_id and e.srel is not distinct from t.subject_relation);

-- 4c: 살아 있는 사용자 객체에 붙은, 역할 규칙에 없는 관리 튜플
insert into drift
select '4c', 'unexpected-manager-tuple', t.id, t.namespace, t.relation, t.object_id, false
from relation_tuples t
where t.namespace = 'user' and t.object_id in (select id from iam_live) and t.id not in (select tuple_id from drift where tuple_id is not null)
  and not exists (select 1 from expected e where e.cat = '4c' and e.ns = t.namespace and e.obj = t.object_id and e.rel = t.relation
                    and e.sns = t.subject_namespace and e.sid = t.subject_id and e.srel is not distinct from t.subject_relation);

-- 5. 스토리지 ↔ DB (보고 전용: --fix-orphans/--auto 는 cat 1·2 만 지운다). 읽기에 실패했으면 건너뛴다.
create temp table s_objs as
  select split_part(path, '/', 1) as bucket, substr(path, length(split_part(path, '/', 1)) + 2) as key, mtime from minio_objects;
insert into drift
select '5a', 'blog-unreferenced', null, o.bucket, '-', o.key, false
from s_objs o, params p
where (select v from storage_ok) and o.bucket = p.blog_bucket
  and o.mtime < (select e from storage_now) - p.sgrace * 60
  and not exists (select 1 from blog_ref_uuids r
                  where r.u = substring(o.key from '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'));
insert into drift
select '5a', 'party-unreferenced', null, o.bucket, '-', o.key, false
from s_objs o, params p
where (select v from storage_ok) and o.bucket = p.party_bucket
  and o.mtime < (select e from storage_now) - p.sgrace * 60
  and not exists (select 1 from party_photo_keys k where k.object_key = o.key);
insert into drift
select '5b', 'photo-missing-in-storage', null, 'pin_photos', '-', k.object_key, false
from party_photo_keys k, params p
where (select v from storage_ok)
  and not exists (select 1 from s_objs o where o.bucket = p.party_bucket and o.key = k.object_key);
SQL
}

report_sql() {
  cat <<'SQL'
select 'RPT', cat, sub, ns, rel, count(*), loud::int, (array_agg(distinct key))[1:3]::text
from drift group by cat, sub, ns, rel, loud order by cat, sub, ns, rel;
select 'TOT', count(*) from relation_tuples;
select 'SKIP', count(*) from relation_tuples where created_at >= now() - (:grace * interval '1 minute');
select 'UNK', namespace, count(*) from relation_tuples
 where namespace not in (select ns from tracked) and namespace not in ('system', 'user') group by namespace order by namespace;
SQL
}

fix_sql() {
  cat <<'SQL'
begin;
do $$ declare n bigint; begin
  select count(*) into n from drift where cat in ('1', '2');
  if n > (select maxdel from params) then raise exception '삭제 대상 % 개가 상한을 넘는다. 아무것도 지우지 않고 중단', n; end if;
end $$;
with d as (delete from relation_tuples where id in (select tuple_id from drift where cat in ('1', '2')) returning 1)
select 'FIX', count(*) from d;
commit;
SQL
}

# 분류를 돌려 결과 줄(RPT|TOT|SKIP|UNK|FIX)을 $WORK/out 에 모은다. $1 = report|fix
run() {
  build_load; write_classify
  { cat "$WORK/load.sql" "$WORK/classify.sql"; report_sql; [ "$1" = fix ] && fix_sql; true; } > "$WORK/run.sql"
  psqlc -d "$GUARD_DB" -v grace="$GRACE_MIN" -v maxdel="$MAX_DELETE" -v sgrace="$STORAGE_GRACE" -v blog_bucket="$BLOG_BUCKET" -v party_bucket="$PARTY_BUCKET" -f - < "$WORK/run.sql" > "$WORK/out" 2>>"$WORK/err" || die "Guard 조회/실행 실패: $(lasterr)"
}

label() {
  case "$1" in
    1) echo "주체가 탈퇴(DELETED)/없는 IAM 사용자 [삭제 대상]" ;;
    2) echo "객체가 서비스 DB 에서 사라진 튜플 [삭제 대상]" ;;
    3) echo "객체는 있는데 소유자/작성자 튜플이 없음 [보고만]" ;;
    4a) echo "!! system 네임스페이스의 IAM 역할로 설명되지 않는 튜플 (권한 과다 위험) [보고만]" ;;
    4b) echo "IAM 역할은 있는데 system:doro 멤버십 튜플이 없음 [보고만]" ;;
    4c) echo "사용자 객체의 manager/super_manager 튜플 불일치 [보고만]" ;;
    5a) echo "스토리지에 있는데 DB 가 쓰지 않는 객체 [보고 전용·삭제 안 함: 블로그는 undo 용으로 일부러 남긴다]" ;;
    5b) echo "DB(pin_photos)에는 있는데 스토리지에 없는 행 = 깨진 사진 [보고 전용]" ;;
  esac
}

print_report() {
  local total skip cat n
  total="$(awk -F'|' '$1=="TOT"{print $2}' "$WORK/out")"; skip="$(awk -F'|' '$1=="SKIP"{print $2}' "$WORK/out")"
  log "전체 튜플 ${total}개 (생성 ${GRACE_MIN}분 이내 ${skip}개는 1·2 분류에서 제외)"
  for cat in 1 2 3 4a 4b 4c 5a 5b; do
    n="$(awk -F'|' -v c="$cat" '$1=="RPT" && $2==c {s+=$6} END{print s+0}' "$WORK/out")"
    printf '\n[%s] %s: %s\n' "$cat" "$(label "$cat")" "$n"
    awk -F'|' -v c="$cat" '$1=="RPT" && $2==c {printf "    %-24s %-14s %-14s %6d개  샘플 ID %s\n", $3, $4, $5, $6, $8}' "$WORK/out"
  done
  if awk -F'|' '$1=="RPT" && $2=="4a"{f=1} END{exit !f}' "$WORK/out"; then
    printf '\n!!!!!! 경고: system 네임스페이스에 IAM 역할로 설명되지 않는 튜플이 있다. 권한이 늘어난 상태일 수 있으니 즉시 확인하라. --fix-orphans 는 이를 지우지 않는다.\n'
  fi
  if awk -F'|' '$1=="UNK"{f=1} END{exit !f}' "$WORK/out"; then
    printf '\n[참고] 점검 대상이 아닌 네임스페이스(개수만):\n'
    awk -F'|' '$1=="UNK"{printf "    %-20s %d개\n", $2, $3}' "$WORK/out"
  fi
}

count_cat() { awk -F'|' -v re="$1" '$1=="RPT" && $2 ~ re {s+=$6} END{print s+0}' "$WORK/out"; }
counts_line() { printf 'c1=%s c2=%s c3=%s c4a=%s c4b=%s c4c=%s c5a=%s c5b=%s' "$(count_cat '^1$')" "$(count_cat '^2$')" "$(count_cat '^3$')" "$(count_cat '^4a$')" "$(count_cat '^4b$')" "$(count_cat '^4c$')" "$(count_cat '^5a$')" "$(count_cat '^5b$')"; }

prune_backups() { # 최근 KEEP_BACKUPS 개만 남긴다
  # shellcheck disable=SC2012
  ls -1t "$BACKUP_DIR"/guard-reconcile-*.sql.gz 2>/dev/null | tail -n +"$((KEEP_BACKUPS + 1))" | while read -r old; do rm -f -- "$old"; done
}

log "점검 시작(모드: $MODE$([ "$AUTO" = true ] && echo ', auto'), DB: $GUARD_DB ↔ $IAM_DB, $BLOG_DB, $PARTY_DB)"
run report
[ "$AUTO" = true ] || print_report
found="$(counts_line)"; alert_before="$(count_cat '^4a$')"
orphans="$(count_cat '^[12]$')"
deleted=0; bk_name=""
if [ "$MODE" = fix ]; then
  if [ "$orphans" = 0 ]; then
    log "삭제할 1·2 분류 튜플이 없다. 할 일 없음"
  else
    [ "$orphans" -le "$MAX_DELETE" ] || die "상한 초과: 삭제 대상 ${orphans}개 > 상한 ${MAX_DELETE}개. 아무것도 지우지 않았다. 내용을 확인하고 --max-delete 로 올려 다시 실행하라 (${found})"
    mkdir -p "$BACKUP_DIR"
    bk="$BACKUP_DIR/guard-reconcile-$GUARD_DB-$(date +%Y%m%d-%H%M%S).sql.gz"
    docker exec "$PG" pg_dump -U "$PGUSER_NAME" -d "$GUARD_DB" </dev/null | gzip > "$bk"
    [ -s "$bk" ] || die "백업이 비어 있다"
    chmod 600 "$bk"
    bk_name="$(basename "$bk")"
    log "백업: $bk ($(du -h "$bk" | cut -f1))"
    run fix
    deleted="$(awk -F'|' '$1=="FIX"{print $2}' "$WORK/out")"
    log "삭제한 튜플: ${deleted}개 (분류 1·2)"
    prune_backups
    log "삭제 뒤 다시 점검"
    run report
    [ "$AUTO" = true ] || print_report
  fi
fi
remaining="$(count_cat '^([1-4]|5b)')"   # 5a(남은 이미지)는 설계상 남을 수 있어 종료 코드·WARN 에 넣지 않는다

if [ "$AUTO" = true ]; then
  body="$found deleted=$deleted left=$remaining${bk_name:+ backup=$bk_name}"
  if [ "$alert_before" != 0 ] || [ "$(count_cat '^4a$')" != 0 ]; then
    auto_summary ALERT "$body 권한 과다 튜플(4a) 있음: system 네임스페이스를 즉시 확인"; exit 3
  elif [ "$remaining" != 0 ]; then
    auto_summary WARN "$body"; exit 0
  fi
  auto_summary OK "$body"; exit 0
fi

if [ "$remaining" = 0 ]; then log "어긋남 없음"; exit 0; fi
log "어긋남 남음: ${remaining}건"
exit 1
