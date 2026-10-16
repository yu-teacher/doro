#!/usr/bin/env bash
# 개발용 시드 계정(dev_*@doro.com 10명)과 그 데이터를 운영에서 지운다. 운영 서버(mini)에서 실행한다.
# 비밀 값은 다루지 않는다. 지우기 전에 pg_dump 백업을 만든다.
#
#   ssh mini 'bash -s -- --check'                < scripts/purge-seed-data.sh   # 읽기 전용: 무엇이 지워지는지
#   ssh mini 'bash -s -- --apply --stage blog'   < scripts/purge-seed-data.sh   # 1단계: 블로그 데이터 삭제 + 카운터 재계산
#   ssh mini 'bash -s -- --apply --stage iam'    < scripts/purge-seed-data.sh   # 2단계: IAM 계정을 탈퇴 대기(유예 만료)로 → 정리 스케줄러가 영구 삭제
#
# 순서가 중요하다: 블로그를 먼저 지운다. IAM 영구 탈퇴가 먼저 돌면 블로그의 탈퇴 동기화가 공개 글을
# '탈퇴한 사용자' 글로 남겨 버린다. 2단계는 블로그에 시드 계정이 남아 있으면 거부한다.
# 시드 판별: 이메일이 정확히 dev_<이름>@doro.com 이고 기대 인원(10명)과 일치해야 한다. 어긋나면 아무것도 하지 않는다.
set -Eeuo pipefail

APPLY=false; STAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --check) ;;
    --apply) APPLY=true ;;
    --stage) STAGE="${2:-}"; shift ;;
    *) echo "사용법: bash -s -- --check | --apply --stage blog|iam" >&2; exit 2 ;;
  esac
  shift
done
PG="${PG_CONTAINER:-doro-postgres}"
PGUSER_NAME="${PG_USER:-doro_admin}"
BLOG_DB="${BLOG_DB:-service_blog}"
IAM_DB="${IAM_DB:-doro_auth}"
EXPECTED="${EXPECTED_SEEDS:-10}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
# LIKE 의 '_' 는 한 글자 와일드카드라 이스케이프한다. 도메인은 정확히 doro.com 이다.
SEED_PATTERN='dev\_%@doro.com'

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
q() { docker exec -i "$PG" psql -U "$PGUSER_NAME" -v ON_ERROR_STOP=1 -tA -F '|' -d "$1"; }

[ "$APPLY" = false ] || [ "$STAGE" = blog ] || [ "$STAGE" = iam ] || die "--apply 는 --stage blog 또는 --stage iam 이 필요하다"
docker inspect "$PG" >/dev/null 2>&1 || die "컨테이너 $PG 가 없다"

blog_seed_count() { echo "select count(*) from blog_users where email like '$SEED_PATTERN';" | q "$BLOG_DB"; }
iam_seed_count()  { echo "select count(*) from users where email like '$SEED_PATTERN';" | q "$IAM_DB"; }

# 블로그·IAM 두 쪽의 시드 ID 가 같은지(같은 계정을 가리키는지) 확인한다.
ids_match() {
  local b i
  b="$(echo "select id from blog_users where email like '$SEED_PATTERN' order by id;" | q "$BLOG_DB")"
  i="$(echo "select id from users where email like '$SEED_PATTERN' and status <> 'DELETED' order by id;" | q "$IAM_DB")"
  [ "$b" = "$i" ]
}

describe() {
  log "== 블로그($BLOG_DB) =="
  q "$BLOG_DB" <<SQL
with seed as (select id from blog_users where email like '$SEED_PATTERN')
select '시드 계정', count(*) from seed
union all select '  글', count(*) from posts where user_id in (select id from seed)
union all select '  댓글', count(*) from comments where user_id in (select id from seed)
union all select '  좋아요', count(*) from post_likes where user_id in (select id from seed)
union all select '  시리즈', count(*) from series where user_id in (select id from seed)
union all select '  팔로우(양방향)', count(*) from user_follows where follower_id in (select id from seed) or following_id in (select id from seed)
union all select '  알림', count(*) from notifications where recipient_id in (select id from seed) or sender_id in (select id from seed)
union all select '  API 키', count(*) from api_keys where user_id in (select id from seed)
union all select '시드가 아닌 계정', count(*) from blog_users where id not in (select id from seed)
union all select '! 시드 글에 달린 비시드 댓글(같이 사라짐)', count(*) from comments c join posts p on p.id=c.post_id where p.user_id in (select id from seed) and c.user_id not in (select id from seed)
union all select '! 시드 글에 달린 비시드 좋아요(같이 사라짐)', count(*) from post_likes l join posts p on p.id=l.post_id where p.user_id in (select id from seed) and l.user_id not in (select id from seed)
union all select '! 시드 계정을 팔로우/팔로잉하는 비시드(카운터 재계산)', count(*) from user_follows where (follower_id in (select id from seed)) <> (following_id in (select id from seed));
SQL
  log "== IAM($IAM_DB) =="
  q "$IAM_DB" <<SQL
select email||' | '||role||' | '||status from users where email like '$SEED_PATTERN' order by email;
SQL
  log "시드 ID 일치(블로그=IAM): $(ids_match && echo 예 || echo 아니오)"
}

guard_counts() {
  local b i
  b="$(blog_seed_count)"; i="$(iam_seed_count)"
  log "시드 수 — 블로그: $b, IAM: $i (기대 $EXPECTED)"
}

backup() {
  local db="$1" f
  mkdir -p "$BACKUP_DIR"
  f="$BACKUP_DIR/purge-seed-$db-$(date +%Y%m%d-%H%M%S).sql.gz"
  docker exec "$PG" pg_dump -U "$PGUSER_NAME" -d "$db" | gzip > "$f"
  [ -s "$f" ] || die "백업이 비어 있다: $f"
  chmod 600 "$f"
  log "백업: $f ($(du -h "$f" | cut -f1))"
}

if [ "$APPLY" = false ]; then describe; guard_counts; exit 0; fi

if [ "$STAGE" = blog ]; then
  b="$(blog_seed_count)"
  if [ "$b" = 0 ]; then log "블로그에 시드 계정이 없다. 할 일 없음(이미 끝남)"; exit 0; fi
  [ "$b" = "$EXPECTED" ] || die "블로그 시드 계정이 $b 명이다(기대 $EXPECTED). 중단"
  ids_match || die "블로그와 IAM 의 시드 ID 가 다르다. 중단"
  backup "$BLOG_DB"
  log "블로그 시드 삭제(트랜잭션)"
  q "$BLOG_DB" <<SQL
begin;
create temp table seed_ids on commit drop as select id from blog_users where email like '$SEED_PATTERN';
-- 시드 글에 붙었던 태그(고아가 되면 지운다). 지우기 전에 기억해 둔다.
create temp table seed_tags on commit drop as
  select distinct pt.tag_id from post_tags pt join posts p on p.id = pt.post_id where p.user_id in (select id from seed_ids);
-- 시드가 아닌 사용자의 카운터가 틀어지는 대상: 시드와 팔로우로 이어진 사용자
create temp table touched_users on commit drop as
  select distinct (case when follower_id in (select id from seed_ids) then following_id else follower_id end) as id
  from user_follows
  where (follower_id in (select id from seed_ids)) <> (following_id in (select id from seed_ids));

delete from blog_users where id in (select id from seed_ids);   -- 글·댓글·좋아요·시리즈·팔로우·알림·API 키가 연쇄 삭제된다

-- 연쇄 삭제가 못 고치는 비정규화 카운터를 다시 계산한다(영향받은 행만)
update posts p set
  like_count    = (select count(*) from post_likes l where l.post_id = p.id),
  comment_count = (select count(*) from comments c where c.post_id = p.id and c.is_deleted = false);
update blog_users u set
  follower_count  = (select count(*) from user_follows f where f.following_id = u.id),
  following_count = (select count(*) from user_follows f where f.follower_id  = u.id)
  where u.id in (select id from touched_users);
update tags t set post_count = coalesce((select count(*) from post_tags pt join posts p on p.id = pt.post_id
                                         where pt.tag_id = t.id and p.status = 'PUBLISHED'), 0);
delete from tags where id in (select tag_id from seed_tags) and not exists (select 1 from post_tags pt where pt.tag_id = tags.id);

select 'blog_users 남음', count(*) from blog_users where email like '$SEED_PATTERN';
commit;
SQL
  log "남은 블로그 계정: $(echo 'select count(*) from blog_users;' | q "$BLOG_DB")  글: $(echo 'select count(*) from posts;' | q "$BLOG_DB")  태그: $(echo 'select count(*) from tags;' | q "$BLOG_DB")"
  log "다음: --apply --stage iam"
  exit 0
fi

# stage iam
[ "$(blog_seed_count)" = 0 ] || die "블로그에 시드 계정이 남아 있다. 먼저 --stage blog 를 끝내야 한다"
i="$(echo "select count(*) from users where email like '$SEED_PATTERN' and status = 'ACTIVE';" | q "$IAM_DB")"
if [ "$i" = 0 ]; then log "IAM 에 ACTIVE 시드 계정이 없다. 할 일 없음(이미 탈퇴 대기이거나 삭제됨)"; exit 0; fi
[ "$i" = "$EXPECTED" ] || die "IAM 시드 계정이 $i 명이다(기대 $EXPECTED). 중단"
echo "select count(*) from users where email like '$SEED_PATTERN' and role <> 'USER';" | q "$IAM_DB" | grep -qx 0 || die "시드 중에 USER 가 아닌 역할이 있다. 중단"
backup "$IAM_DB"
log "IAM 시드 계정을 탈퇴 대기(유예 만료 상태)로 바꾼다"
q "$IAM_DB" <<SQL
begin;
update users set status = 'PENDING_DELETION', deletion_requested_at = now() - interval '31 days'
 where email like '$SEED_PATTERN' and status = 'ACTIVE' and role = 'USER';
commit;
select email||' | '||status from users where email like '$SEED_PATTERN' order by email;
SQL
log "영구 삭제는 정리 스케줄러(최대 1시간 안)가 한다. 확인: ssh mini 'bash -s -- --check' < scripts/purge-seed-data.sh"
