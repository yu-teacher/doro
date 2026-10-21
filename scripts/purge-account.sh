#!/usr/bin/env bash
# 계정 하나(이메일로 지정)를 운영에서 지운다. 운영 서버(mini)에서 실행한다. 비밀 값은 다루지 않는다. 지우기 전에 IAM DB 를 pg_dump 로 백업한다.
# 시드 정리(purge-seed-data.sh)와 같은 경로를 쓴다: IAM 계정을 탈퇴 대기(유예 만료)로 바꾸면 정리 스케줄러(최대 1시간)가 영구 삭제하고,
# 그 사이 각 서비스는 탈퇴 동기화(IAM /internal/v1/deleted-users 폴링)로 자기 데이터를 익명화하고 Guard 튜플·역할을 정리한다.
#
#   ssh mini 'bash -s -- --check --email yusm@doro.local' < scripts/purge-account.sh   # 읽기 전용: 계정과 서비스별 데이터, 지워도 되는지
#   ssh mini 'bash -s -- --apply --email yusm@doro.local' < scripts/purge-account.sh   # 탈퇴 대기(유예 만료)로 바꾼다 → 스케줄러가 영구 삭제
#
# 안전장치(하나라도 어긋나면 아무것도 하지 않는다)
#  - 이메일이 정확히 일치하는 살아 있는 계정이 1명일 것
#  - 그 계정이 최고 관리자(SUPER_ADMIN)이면, 지운 뒤에도 살아 있는 최고 관리자가 1명 이상 남을 것(잠금 방지)
#  - 상태가 ACTIVE 일 것(이미 탈퇴 대기/삭제면 "할 일 없음")
# 서비스의 공개 글·댓글은 탈퇴 동기화가 '탈퇴한 사용자' 글로 남긴다(삭제하지 않는다). 글까지 지우려면 먼저 그 서비스 데이터를 따로 지운다(--check 가 개수를 보여 준다).
set -Eeuo pipefail

APPLY=false; EMAIL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --check) ;;
    --apply) APPLY=true ;;
    --email) EMAIL="${2:-}"; shift ;;
    *) echo "사용법: bash -s -- --check|--apply --email <이메일>" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$EMAIL" ] || { echo "--email 이 필요하다" >&2; exit 2; }
case "$EMAIL" in *[!A-Za-z0-9._@+-]*|"") echo "이메일 형식이 올바르지 않다" >&2; exit 2 ;; esac
case "$EMAIL" in *@*) ;; *) echo "이메일 형식이 올바르지 않다" >&2; exit 2 ;; esac

PG="${PG_CONTAINER:-doro-postgres}"
PGUSER_NAME="${PG_USER:-doro_admin}"
IAM_DB="${IAM_DB:-doro_auth}"
BLOG_DB="${BLOG_DB:-service_blog}"
PARTY_DB="${PARTY_DB:-service_party}"
GAMES_DB="${GAMES_DB:-service_games}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
q() { docker exec -i "$PG" psql -U "$PGUSER_NAME" -v ON_ERROR_STOP=1 -tA -F '|' -d "$1"; }
UUID_RE='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

docker inspect "$PG" >/dev/null 2>&1 || die "컨테이너 $PG 가 없다"

# 이메일은 위에서 허용 문자만 통과시켰으므로 SQL 리터럴에 그대로 넣어도 안전하다(작은따옴표 불가).
rows="$(echo "select id||'|'||role||'|'||status from users where lower(email) = lower('$EMAIL') and status <> 'DELETED';" | q "$IAM_DB")"
n="$(printf '%s\n' "$rows" | grep -c . || true)"
[ "$n" = 1 ] || die "이메일 '$EMAIL' 와 일치하는 살아 있는 계정이 ${n}개다(정확히 1개여야 한다)"
IFS='|' read -r ID ROLE STATUS <<<"$rows"
[[ "$ID" =~ $UUID_RE ]] || die "계정 ID 형식이 이상하다. 중단"
supers="$(echo "select count(*) from users where role = 'SUPER_ADMIN' and status = 'ACTIVE';" | q "$IAM_DB")"

log "계정: $EMAIL | 역할 $ROLE | 상태 $STATUS | id ${ID:0:8}… | 살아 있는 최고 관리자 $supers 명"
count_in() { # count_in <db> <sql> : 테이블이 없으면 '-'
  local out; out="$(echo "$2" | q "$1" 2>/dev/null)" && printf '%s' "$out" || printf '%s' '-'
}
log "서비스 데이터(참고)"
log "  블로그 — 글 $(count_in "$BLOG_DB" "select count(*) from posts where user_id = '$ID'"), 댓글 $(count_in "$BLOG_DB" "select count(*) from comments where user_id = '$ID'"), 시리즈 $(count_in "$BLOG_DB" "select count(*) from series where user_id = '$ID'")"
log "  파티 — 지도 $(count_in "$PARTY_DB" "select count(*) from party_maps where owner_id = '$ID'"), 그 지도의 핀 $(count_in "$PARTY_DB" "select count(*) from pins where map_id in (select id from party_maps where owner_id = '$ID')")"
log "  게임 — 최고 기록 $(count_in "$GAMES_DB" "select count(*) from game_records where user_id = '$ID'")  (조회가 안 되면 '-')"

[ "$STATUS" = ACTIVE ] || { log "상태가 $STATUS 라 할 일 없음(이미 탈퇴 대기이거나 삭제 진행 중)"; exit 0; }
if [ "$ROLE" = SUPER_ADMIN ] && [ "$supers" -lt 2 ]; then die "이 계정이 마지막 최고 관리자다(지우면 잠긴다). 중단"; fi
if [ "$APPLY" = false ]; then log "지워도 된다. --apply 로 탈퇴 대기(유예 만료)로 바꾼다(읽기 전용 점검이었다)"; exit 0; fi

mkdir -p "$BACKUP_DIR"
bk="$BACKUP_DIR/purge-account-$IAM_DB-$(date +%Y%m%d-%H%M%S).sql.gz"
docker exec "$PG" pg_dump -U "$PGUSER_NAME" -d "$IAM_DB" | gzip > "$bk"
[ -s "$bk" ] || die "백업이 비어 있다: $bk"
chmod 600 "$bk"; log "백업: $bk"
res="$(q "$IAM_DB" <<SQL
begin;
update users set status = 'PENDING_DELETION', deletion_requested_at = now() - interval '31 days'
 where id = '$ID' and status = 'ACTIVE';
select status from users where id = '$ID';
commit;
SQL
)"
printf '%s\n' "$res" | grep -qx 'PENDING_DELETION' || die "상태가 바뀌지 않았다: $res"
log "탈퇴 대기로 바꿨다. 영구 삭제는 정리 스케줄러(최대 1시간 안)가 한다. 확인: ssh mini 'bash -s -- --check --email $EMAIL' < scripts/purge-account.sh"
