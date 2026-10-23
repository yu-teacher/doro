#!/usr/bin/env bash
# 서비스별 DB 계정 분리: 지금은 모든 서비스가 슈퍼유저 doro_admin 으로 같은 Postgres 에 접속한다. 서비스마다 자기 DB 의 소유자인
# 일반 계정(<서비스>_app)을 만들고, 그 계정으로만 접속하게 바꾼다. 한 서비스가 뚫려도 다른 서비스의 DB 를 읽지 못한다.
# 운영 서버(mini)에서 실행한다. 비밀번호는 서버에서 만들어 .env 에만 쓰고 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --check'                  < scripts/split-db-accounts.sh   # 읽기 전용: 서비스별 현재 접속 계정과 준비 상태
#   ssh mini 'bash -s -- --apply blog'             < scripts/split-db-accounts.sh   # 한 서비스만 전환(blog|party|games|interview|auth|guard|bff)
#   ssh mini 'bash -s -- --rotate blog'            < scripts/split-db-accounts.sh   # 이미 전환된 서비스의 비밀번호만 새로 정하고 .env 에 쓴 뒤 그 서비스만 다시 만든다(실패하면 .env 복구)
#   ssh mini 'bash -s -- --rollback blog'          < scripts/split-db-accounts.sh   # .env 를 전환 전으로 되돌리고 그 서비스만 다시 만든다
#
# --apply <서비스> 가 하는 일 (이미 끝난 부분은 건너뛴다)
#   1) 그 서비스 DB 를 pg_dump 로 백업
#   2) 계정 <서비스>_app 생성(LOGIN 만, 슈퍼유저·DB 생성·역할 생성 권한 없음), 새 비밀번호
#   3) DB 와 그 안의 객체(테이블·시퀀스·뷰·함수·타입·public 스키마) 소유자를 그 계정으로 바꾸고,
#      PUBLIC 의 CONNECT 를 거두어 그 계정(과 슈퍼유저)만 접속하게 한다
#   4) 새 계정으로 실제 로그인을 확인(비밀번호 인증을 거치는 경로)
#   5) 서비스의 .env 에 접속 계정·비밀번호를 쓰고(백업 후) 그 서비스 컨테이너만 다시 만든다 → healthy 와 접속 계정 확인
#      healthy 가 되지 않으면 .env 를 되돌리고 다시 만든다. 계정·소유자는 그대로 둬도 슈퍼유저가 계속 접속할 수 있어 안전하다.
# 순서 권장: games → party → blog(코드 변경 없음) → 그다음 Doro 의 compose 변경을 배포하고 auth → guard → bff.
# auth/guard/bff 는 Doro 의 docker-compose.yml 이 <서비스>_DB_USER 변수를 받아야 한다(없으면 중단한다).
set -Eeuo pipefail

ACTION=""; SVC=""; FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --check|--apply|--rollback|--rotate) ACTION="$1" ;;
    blog|party|games|interview|auth|guard|bff) SVC="$1" ;;
    *) echo "사용법: bash -s -- --check | --apply <서비스> | --rotate <서비스> | --rollback <서비스>   (서비스: blog party games interview auth guard bff)" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$ACTION" ] || { echo "사용법: bash -s -- --check | --apply <서비스> | --rotate <서비스> | --rollback <서비스>" >&2; exit 2; }
{ [ "$ACTION" = --check ] || [ -n "$SVC" ]; } || { echo "--apply/--rollback/--rotate 은 서비스 이름이 필요하다" >&2; exit 2; }

PG="${PG_CONTAINER:-doro-postgres}"
ADMIN="${PG_ADMIN_USER:-doro_admin}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
DORO_ENV="${DORO_ENV:-$HOME/doro/.env}"
ALL="blog party games interview auth guard bff"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
get_var() { grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- || true; }
set_var() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "$file.XXXXXX")"
  grep -v "^$key=" "$file" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  chmod 600 "$tmp"; mv "$tmp" "$file"
}
psql_admin() { docker exec -i "$PG" psql -U "$ADMIN" -v ON_ERROR_STOP=1 -tA -F '|' "$@"; }
healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 "${HEALTH_TRIES:-60}"); do healthy "$1" && return 0; sleep "${HEALTH_SLEEP:-3}"; done; return 1; }

# 서비스별 설정: DB 이름, 계정 이름, .env 파일과 변수 이름, 컨테이너, 다시 만드는 방법
cfg() {
  case "$1" in
    blog)  DB=service_blog;  ROLE=blog_app;  KIND=sibling; DIR="$HOME/doro-blog";  SERVICE=blog-backend; CONTAINER=doro-blog-backend; USERVAR=DB_USER; PASSVAR=DB_PASSWORD ;;
    party) DB=service_party; ROLE=party_app; KIND=sibling; DIR="$HOME/doro-party"; SERVICE=party-api;    CONTAINER=doro-party-api;    USERVAR=DB_USER; PASSVAR=DB_PASSWORD ;;
    games) DB=service_games; ROLE=games_app; KIND=sibling; DIR="$HOME/doro-games"; SERVICE=games-api;    CONTAINER=doro-games-api;    USERVAR=DB_USER; PASSVAR=DB_PASSWORD ;;
    interview) DB=service_interview; ROLE=interview_app; KIND=sibling; DIR="$HOME/doro-interview"; SERVICE=interview-api; CONTAINER=doro-interview-api; USERVAR=DB_USER; PASSVAR=DB_PASSWORD ;;
    auth)  DB=doro_auth;     ROLE=auth_app;  KIND=doro; DIR="$RUNNER_DIR"; SERVICE=auth-api;  CONTAINER=doro-auth-api;  USERVAR=AUTH_DB_USER;  PASSVAR=AUTH_DB_PASSWORD ;;
    guard) DB=doro_guard;    ROLE=guard_app; KIND=doro; DIR="$RUNNER_DIR"; SERVICE=guard-api; CONTAINER=doro-guard-api; USERVAR=GUARD_DB_USER; PASSVAR=GUARD_DB_PASSWORD ;;
    bff)   DB=doro_bff;      ROLE=bff_app;   KIND=doro; DIR="$RUNNER_DIR"; SERVICE=bff-api;   CONTAINER=doro-bff-api;   USERVAR=BFF_DB_USER;   PASSVAR=BFF_DB_PASSWORD ;;
  esac
  if [ "$KIND" = sibling ]; then ENV_FILE="$DIR/.env"; COMPOSE_FILE="$DIR/docker-compose.prod.yml"; else ENV_FILE="$DORO_ENV"; COMPOSE_FILE="$DIR/docker-compose.yml"; fi
}
recreate() {
  if [ "$KIND" = sibling ]; then ( cd "$DIR" && docker compose -f docker-compose.prod.yml up -d --no-build --no-deps "$SERVICE" ) >/dev/null
  else ( cd "$DIR" && docker compose -p doro --env-file "$ENV_FILE" up -d --no-build --no-deps "$SERVICE" ) >/dev/null; fi
}
# 컨테이너가 실제로 쓰는 DB 계정(비밀번호는 읽지 않는다)
container_user() {
  docker inspect "$CONTAINER" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep -E '^(DB_USER|SPRING_DATASOURCE_USERNAME)=' | head -1 | cut -d= -f2-
}
compose_takes_var() { grep -q "$USERVAR" "$COMPOSE_FILE" 2>/dev/null; }
role_exists() { [ "$(echo "select count(*) from pg_roles where rolname = '$ROLE';" | psql_admin -d postgres)" = 1 ]; }
db_owner() { echo "select pg_get_userbyid(datdba) from pg_database where datname = '$DB';" | psql_admin -d postgres; }
conn_users() { echo "select coalesce(string_agg(usename || ':' || n, ' '), '-') from (select usename, count(*) n from pg_stat_activity where datname = '$DB' and pid <> pg_backend_pid() group by 1 order by 1) t;" | psql_admin -d postgres; }
# 비밀번호 인증을 거치는 로그인(컨테이너 자기 IP 로 접속 — 127.0.0.1 은 trust 일 수 있어 검증이 안 된다)
login_ok() {
  local pw="$1" ip
  ip="$(docker exec "$PG" hostname -i | awk '{print $1}')"
  docker exec -e PGPASSWORD="$pw" "$PG" psql -h "$ip" -U "$ROLE" -d "$DB" -tAc 'select 1' 2>/dev/null | grep -qx 1
}

describe_one() {
  cfg "$1"
  local cu; cu="$(container_user || true)"
  log "[$1] DB $DB 소유자: $(db_owner) | 컨테이너 접속 계정: ${cu:-(컨테이너 없음)} | 현재 연결: $(conn_users)"
  log "[$1]   계정 $ROLE: $(role_exists && echo 있음 || echo 없음) | .env 의 $USERVAR: $([ -n "$(get_var "$ENV_FILE" "$USERVAR")" ] && get_var "$ENV_FILE" "$USERVAR" || echo 없음)$([ "$KIND" = doro ] && printf ' | compose 가 변수를 받음: %s' "$(compose_takes_var && echo 예 || echo 아니오)")"
}

own_objects_sql() {
  cat <<SQL
alter database "$DB" owner to "$ROLE";
alter schema public owner to "$ROLE";
do \$do\$
declare r record;
begin
  for r in select schemaname, tablename from pg_tables where schemaname = 'public' loop
    execute format('alter table %I.%I owner to %I', r.schemaname, r.tablename, '$ROLE');
  end loop;
  for r in select schemaname, sequencename from pg_sequences where schemaname = 'public' loop
    execute format('alter sequence %I.%I owner to %I', r.schemaname, r.sequencename, '$ROLE');
  end loop;
  for r in select schemaname, viewname from pg_views where schemaname = 'public' loop
    execute format('alter view %I.%I owner to %I', r.schemaname, r.viewname, '$ROLE');
  end loop;
  for r in select schemaname, matviewname from pg_matviews where schemaname = 'public' loop
    execute format('alter materialized view %I.%I owner to %I', r.schemaname, r.matviewname, '$ROLE');
  end loop;
  for r in select p.oid::regprocedure as sig from pg_proc p
           where p.pronamespace = 'public'::regnamespace
             and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e') loop
    execute format('alter function %s owner to %I', r.sig, '$ROLE');
  end loop;
  for r in select t.typname from pg_type t
           where t.typnamespace = 'public'::regnamespace and t.typtype in ('e', 'd')
             and not exists (select 1 from pg_depend d where d.objid = t.oid and d.deptype = 'e') loop
    execute format('alter type public.%I owner to %I', r.typname, '$ROLE');
  end loop;
end
\$do\$;
revoke connect on database "$DB" from public;
grant connect on database "$DB" to "$ROLE";
SQL
}

apply_one() {
  cfg "$1"
  [ -f "$ENV_FILE" ] || die ".env 가 없다: $ENV_FILE"
  [ -f "$COMPOSE_FILE" ] || die "compose 파일이 없다: $COMPOSE_FILE"
  [ "$KIND" = sibling ] || compose_takes_var || die "$COMPOSE_FILE 이 $USERVAR 를 받지 않는다. 먼저 Doro 의 compose 변경(서비스별 DB 계정 변수)을 배포한다"
  healthy "$CONTAINER" || die "$CONTAINER 가 healthy 가 아니다. 먼저 정상으로 만든다"
  local cur_user cur_pw ts bak new_pw
  cur_user="$(get_var "$ENV_FILE" "$USERVAR")"; cur_pw="$(get_var "$ENV_FILE" "$PASSVAR")"
  if [ "$FORCE" = 0 ] && [ "$cur_user" = "$ROLE" ] && [ -n "$cur_pw" ] && login_ok "$cur_pw" && [ "$(container_user || true)" = "$ROLE" ] && [ "$(db_owner)" = "$ROLE" ]; then
    log "[$1] 이미 $ROLE 로 접속 중이다(할 일 없음)"; return 0
  fi
  ts="$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$BACKUP_DIR"
  bak="$BACKUP_DIR/dbsplit-$DB-$ts.sql.gz"
  docker exec "$PG" pg_dump -U "$ADMIN" -d "$DB" | gzip > "$bak"
  [ -s "$bak" ] || die "백업이 비어 있다: $bak"
  chmod 600 "$bak"; log "[$1] DB 백업: $bak"

  new_pw="$(openssl rand -hex 24)"
  if role_exists; then
    echo "alter role \"$ROLE\" with login nosuperuser nocreatedb nocreaterole noreplication password '$new_pw';" | psql_admin -d postgres >/dev/null
    log "[$1] 계정 $ROLE 은 이미 있어 비밀번호만 새로 정했다"
  else
    echo "create role \"$ROLE\" with login nosuperuser nocreatedb nocreaterole noreplication password '$new_pw';" | psql_admin -d postgres >/dev/null
    log "[$1] 계정 $ROLE 을 만들었다(LOGIN 만)"
  fi
  own_objects_sql | psql_admin -d "$DB" >/dev/null
  log "[$1] $DB 의 소유자를 $ROLE 로 바꾸고 PUBLIC CONNECT 를 거두었다"
  login_ok "$new_pw" || die "새 계정으로 로그인하지 못했다. .env 는 바꾸지 않았다(계정·소유자는 슈퍼유저 접속에 영향이 없다)"
  log "[$1] 새 계정 로그인 확인(비밀번호 인증)"

  cp -p "$ENV_FILE" "$ENV_FILE.bak-dbsplit-$1-$ts"; log "[$1] .env 백업: $ENV_FILE.bak-dbsplit-$1-$ts"
  set_var "$ENV_FILE" "$USERVAR" "$ROLE"
  set_var "$ENV_FILE" "$PASSVAR" "$new_pw"
  recreate
  if wait_healthy "$CONTAINER" && [ "$(container_user || true)" = "$ROLE" ]; then
    sleep "${HEALTH_SLEEP:-3}"
    log "[$1] $CONTAINER healthy, 접속 계정 $ROLE. 현재 DB 연결: $(conn_users)"
    return 0
  fi
  if [ "$FORCE" = 1 ]; then
    # 교체 중에는 옛 비밀번호가 이미 무효라 .env 를 되돌리면 서비스가 접속하지 못한다. 새 비밀번호를 그대로 두고 알린다.
    die "[$1] $CONTAINER 가 healthy 로 돌아오지 않았다. 새 비밀번호는 .env 와 계정에 이미 반영돼 있다(되돌리지 않음). docker logs $CONTAINER 를 확인한다"
  fi
  log "[$1] $CONTAINER 가 healthy 로 돌아오지 않았다 — .env 를 되돌린다"
  cp -p "$ENV_FILE.bak-dbsplit-$1-$ts" "$ENV_FILE"; recreate || true
  wait_healthy "$CONTAINER" || log "되돌린 뒤에도 healthy 가 아니다. docker logs $CONTAINER 를 확인한다"
  die "되돌렸다(백업과 새 계정은 그대로 있다)"
}

rollback_one() {
  cfg "$1"
  local latest; latest="$(ls -1t "$ENV_FILE".bak-dbsplit-"$1"-* 2>/dev/null | head -1 || true)"
  [ -n "$latest" ] || die "[$1] 되돌릴 .env 백업이 없다"
  cp -p "$latest" "$ENV_FILE"; log "[$1] .env 를 $latest 로 되돌렸다"
  recreate
  wait_healthy "$CONTAINER" && log "[$1] $CONTAINER healthy, 접속 계정 $(container_user || true)" || die "되돌린 뒤 $CONTAINER 가 healthy 가 아니다"
}

docker inspect "$PG" >/dev/null 2>&1 || die "컨테이너 $PG 가 없다"
case "$ACTION" in
  --check) for s in $ALL; do describe_one "$s"; done ;;
  --apply) apply_one "$SVC" ;;
  --rotate) FORCE=1; apply_one "$SVC" ;;
  --rollback) rollback_one "$SVC" ;;
esac
