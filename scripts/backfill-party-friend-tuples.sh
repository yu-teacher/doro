#!/usr/bin/env bash
# 도로 파티의 친구 목록 튜플(party_friends:<A>#friend@user:<B>)을 DB 의 친구 관계(friendships)에 맞춰 빠진 것만 채운다.
# 친구 목록 튜플 기능이 생기기 전에 맺은 관계에는 튜플이 없다(지도를 친구 전체에 공개할 때 파티가 채우는 것과 같은 결과).
# 운영 서버(mini)에서 실행한다. 비밀 값은 다루지 않는다. 있는 튜플은 건드리지 않고, 지우지 않으며, 여러 번 돌려도 같다.
#
#   ssh mini 'bash -s -- --check' < scripts/backfill-party-friend-tuples.sh   # 읽기 전용: 빠진 튜플 목록
#   ssh mini 'bash -s -- --apply' < scripts/backfill-party-friend-tuples.sh   # doro_guard 백업 후 빠진 튜플만 추가
#
# 기대하는 튜플: friendships 에서 status='ACCEPTED' 이고 두 사용자가 모두 IAM 에서 탈퇴하지 않은 쌍의 양방향 2개.
# 안전장치: 한 번에 추가하는 수가 상한(기본 50)을 넘으면 아무것도 하지 않는다(대량 이상 징후는 사람이 본다).
set -Eeuo pipefail

APPLY=false
for a in "$@"; do
  case "$a" in
    --check) ;;
    --apply) APPLY=true ;;
    *) echo "사용법: bash -s -- --check | --apply" >&2; exit 2 ;;
  esac
done
PG="${PG_CONTAINER:-doro-postgres}"
PGUSER_NAME="${PG_USER:-doro_admin}"
PARTY_DB="${PARTY_DB:-service_party}"
IAM_DB="${IAM_DB:-doro_auth}"
GUARD_DB="${GUARD_DB:-doro_guard}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
MAX_ADD="${MAX_ADD:-50}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
q() { docker exec -i "$PG" psql -U "$PGUSER_NAME" -v ON_ERROR_STOP=1 -tA -F '|' -d "$1"; }
UUID_RE='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

docker inspect "$PG" >/dev/null 2>&1 || die "컨테이너 $PG 가 없다"

live="$(echo "select id from users where status <> 'DELETED';" | q "$IAM_DB" | sort)"
[ -n "$live" ] || die "IAM 에서 활성 사용자를 읽지 못했다(잘못된 DB?). 중단"
pairs="$(echo "select user_low_id || '|' || user_high_id from friendships where status = 'ACCEPTED';" | q "$PARTY_DB")"

expected="$(
  while IFS='|' read -r low high; do
    [ -n "${low:-}" ] || continue
    [[ "$low" =~ $UUID_RE && "$high" =~ $UUID_RE ]] || die "형식이 이상한 ID: 중단"
    printf '%s\n' "$live" | grep -qx "$low" && printf '%s\n' "$live" | grep -qx "$high" || continue
    printf '%s|%s\n%s|%s\n' "$low" "$high" "$high" "$low"
  done <<< "$pairs" | sort -u
)"
existing="$(echo "select object_id || '|' || subject_id from relation_tuples where namespace = 'party_friends' and relation = 'friend' and subject_namespace = 'user' and subject_relation is null;" | q "$GUARD_DB" | sort -u)"
missing="$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$existing") | grep . || true)"
n_expected="$(printf '%s\n' "$expected" | grep -c . || true)"
n_missing="$(printf '%s\n' "$missing" | grep -c . || true)"

log "친구 목록 튜플: 기대 $n_expected 개, 빠진 것 $n_missing 개"
if [ "$n_missing" = 0 ]; then log "채울 것이 없다"; exit 0; fi
printf '%s\n' "$missing" | while IFS='|' read -r owner friend; do log "  빠짐: party_friends:$owner#friend@user:$friend"; done
[ "$APPLY" = true ] || { log "--apply 로 추가한다(읽기 전용 점검이었다)"; exit 0; }
[ "$n_missing" -le "$MAX_ADD" ] || die "빠진 튜플이 $n_missing 개로 상한 $MAX_ADD 를 넘는다. 아무것도 하지 않는다"

mkdir -p "$BACKUP_DIR"
bk="$BACKUP_DIR/backfill-party-friends-$GUARD_DB-$(date +%Y%m%d-%H%M%S).sql.gz"
docker exec "$PG" pg_dump -U "$PGUSER_NAME" -d "$GUARD_DB" | gzip > "$bk"
[ -s "$bk" ] || die "백업이 비어 있다: $bk"
chmod 600 "$bk"; log "백업: $bk"

values="$(printf '%s\n' "$missing" | while IFS='|' read -r owner friend; do
  printf "(gen_random_uuid(),'party_friends','%s','friend','user','%s',NULL),\n" "$owner" "$friend"
done)"
values="${values%,}"
q "$GUARD_DB" <<SQL
begin;
insert into relation_tuples (id, namespace, object_id, relation, subject_namespace, subject_id, subject_relation)
values
$values
on conflict do nothing;
commit;
SQL
after="$(echo "select count(*) from relation_tuples where namespace = 'party_friends';" | q "$GUARD_DB")"
log "추가 완료. 현재 party_friends 튜플 $after 개. 확인: ssh mini '~/ops/guard-reconcile.sh --report'"
