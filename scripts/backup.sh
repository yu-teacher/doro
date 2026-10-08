#!/usr/bin/env bash
# Doro 운영 서버(mini) 정기 백업.
#
#   backup.sh backup          전체 백업 (DB 전부 + 역할, MinIO, Redis, 설정) -> 검증 -> 세대 정리 -> (설정 시) 오프사이트 복사
#   backup.sh verify-restore  가장 최근 백업을 임시 PostgreSQL/Redis 에 실제로 복원해 본다 (주 1회 권장)
#   backup.sh status          마지막 성공/복원 테스트 시각과 경고를 출력한다 (읽기 전용)
#
# 서버에 설치: scripts/install-backup.sh 참고. cron 이 backup(매일)과 verify-restore(주 1회)를 실행한다.
# 비밀번호를 읽거나 출력하지 않는다. (설정 파일 tar 에는 .env 가 들어가므로 백업 디렉터리 권한은 700/600 이다.)
set -Eeuo pipefail
umask 077

# 비밀이 아닌 설정(예: OFFSITE_TARGET)은 ~/ops/backup.env 에서 읽는다. cron 줄을 바꾸지 않고 설정할 수 있다.
BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-$HOME/ops/backup.env}"
# shellcheck disable=SC1090
[ -f "$BACKUP_ENV_FILE" ] && . "$BACKUP_ENV_FILE"

ROOT="${BACKUP_ROOT:-$HOME/backups/auto}"
KEEP_DAILY="${KEEP_DAILY:-7}"
KEEP_WEEKLY="${KEEP_WEEKLY:-4}"
KEEP_MONTHLY="${KEEP_MONTHLY:-6}"
MIN_FREE_MB="${MIN_FREE_MB:-2000}"
STALE_HOURS="${STALE_HOURS:-30}"
RESTORE_STALE_DAYS="${RESTORE_STALE_DAYS:-9}"

PG_CONTAINER="${PG_CONTAINER:-doro-postgres}"
REDIS_CONTAINER="${REDIS_CONTAINER:-doro-redis}"
MINIO_CONTAINER="${MINIO_CONTAINER:-doro-minio}"
DORO_DIR="${DORO_DIR:-$HOME/doro}"
BLOG_DIR="${BLOG_DIR:-$HOME/doro-blog}"
TOOLBOX_IMAGE="${TOOLBOX_IMAGE:-postgres:16-alpine}"   # tar/gzip 와 pg 클라이언트가 들어 있는 이미 있는 이미지
REDIS_IMAGE="${REDIS_IMAGE:-redis:7-alpine}"
# 같은 디스크 밖의 사본 위치(rsync/ssh). 공백으로 여러 곳을 줄 수 있다. 예: "pi-backup:./ notebook-backup:./"
# 비어 있으면 오프사이트 복사를 건너뛴다. ~/ops/backup.env 로도 지정할 수 있다. (예전 단일 OFFSITE_TARGET 도 계속 읽는다)
OFFSITE_TARGETS="${OFFSITE_TARGETS:-${OFFSITE_TARGET:-}}"

STATUS_FILE="$ROOT/STATUS"
LOG_FILE="$ROOT/backup.log"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

set_status() { # key=value 를 STATUS 파일에 갱신한다
  local key="$1" value="$2"
  touch "$STATUS_FILE"
  grep -v "^${key}=" "$STATUS_FILE" > "$STATUS_FILE.tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$STATUS_FILE.tmp"
  mv "$STATUS_FILE.tmp" "$STATUS_FILE"
}
get_status() { grep "^$1=" "$STATUS_FILE" 2>/dev/null | cut -d= -f2- || true; }

pg_exec() { docker exec -i "$PG_CONTAINER" sh -c "$1"; }

list_databases() {
  echo "select datname from pg_database where datname not in ('postgres','template0','template1') order by 1" \
    | pg_exec 'psql -U "$POSTGRES_USER" -d postgres -tA'
}

latest_daily() { ls -1d "$ROOT"/daily/[0-9]* 2>/dev/null | sort | tail -1 || true; }

# ------------------------------------------------------------------ backup
do_backup() {
  mkdir -p "$ROOT"/daily "$ROOT"/weekly "$ROOT"/monthly
  exec 9>"$ROOT/.lock"
  flock -n 9 || die "다른 백업이 실행 중이다"

  local free_mb; free_mb="$(df --output=avail -BM "$ROOT" | tail -1 | tr -dc 0-9)"
  [ "$free_mb" -gt "$MIN_FREE_MB" ] || die "디스크 여유가 ${free_mb}MB 로 부족하다 (최소 ${MIN_FREE_MB}MB)"
  for c in "$PG_CONTAINER" "$REDIS_CONTAINER" "$MINIO_CONTAINER"; do
    docker inspect "$c" >/dev/null 2>&1 || die "컨테이너 $c 가 없다"
  done

  local stamp
  stamp="$(date +%Y%m%d-%H%M%S)"
  dir="$ROOT/daily/.${stamp}.partial"   # 전역: EXIT 트랩에서 정리할 수 있어야 한다
  mkdir -p "$dir"
  trap 'rc=$?; if [ $rc -ne 0 ]; then rm -rf "$dir"; set_status result "FAIL"; set_status last_failure "$(date +%FT%T%z)"; log "백업 실패 (exit $rc)"; fi' EXIT

  log "== 백업 시작: $stamp =="

  # 1) PostgreSQL: 역할(globals) + 데이터베이스별 custom 포맷 덤프
  pg_exec 'pg_dumpall -U "$POSTGRES_USER" --globals-only' > "$dir/globals.sql"
  [ -s "$dir/globals.sql" ] || die "globals 덤프가 비어 있다"
  local db tables total_tables=0
  for db in $(list_databases); do
    pg_exec "pg_dump -U \"\$POSTGRES_USER\" -Fc $db" > "$dir/$db.dump"
    [ -s "$dir/$db.dump" ] || die "$db 덤프가 비어 있다"
    tables="$(pg_exec 'pg_restore --list' < "$dir/$db.dump" | grep -c 'TABLE DATA' || true)"
    [ "$tables" -gt 0 ] || die "$db 덤프를 읽을 수 없거나 테이블 데이터가 없다"
    total_tables=$((total_tables + tables))
    log "  PostgreSQL $db: $(du -h "$dir/$db.dump" | cut -f1), 테이블 데이터 ${tables}개"
  done

  # 2) MinIO 데이터 볼륨 (블로그 이미지)
  local minio_volume
  minio_volume="$(docker inspect "$MINIO_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}')"
  [ -n "$minio_volume" ] || die "MinIO 데이터 볼륨을 찾을 수 없다"
  # 컨테이너는 root 로 실행되므로, 만든 파일의 소유자를 현재 사용자로 되돌려야 호스트에서 보관/정리할 수 있다.
  docker run --rm -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" -v "$minio_volume":/data:ro -v "$dir":/backup "$TOOLBOX_IMAGE" \
    sh -c 'tar czf /backup/minio-data.tgz -C /data . && chown "$HOST_UID:$HOST_GID" /backup/minio-data.tgz && chmod 600 /backup/minio-data.tgz'
  log "  MinIO: $(du -h "$dir/minio-data.tgz" | cut -f1), 파일 $(tar tzf "$dir/minio-data.tgz" | grep -vc '/$' || true)개"

  # 3) Redis: RDB 저장 후 복사 (JWT 서명 키 등 영속 상태가 들어 있다)
  redis_cli() { docker exec "$REDIS_CONTAINER" sh -c "REDISCLI_AUTH=\"\${REDIS_PASSWORD:-}\" redis-cli --no-auth-warning $1"; }
  redis_cli BGSAVE >/dev/null
  sleep 1
  local saved=false
  for _ in $(seq 1 30); do
    if redis_cli 'INFO persistence' | tr -d '\r' | grep -q '^rdb_bgsave_in_progress:0$'; then saved=true; break; fi
    sleep 1
  done
  [ "$saved" = true ] || die "Redis BGSAVE 가 완료되지 않았다"
  redis_cli 'INFO persistence' | tr -d '\r' | grep -q '^rdb_last_bgsave_status:ok$' || die "Redis BGSAVE 가 실패했다"
  docker cp "$REDIS_CONTAINER:/data/dump.rdb" "$dir/redis-dump.rdb" >/dev/null
  log "  Redis: $(du -h "$dir/redis-dump.rdb" | cut -f1)"

  # 4) 설정 (.env 포함 -> 권한 600). 인증서 개인키는 certbot 이 재발급하므로 갱신 설정만 담는다.
  local files=() f
  for f in "$DORO_DIR/.env" "$DORO_DIR/docker-compose.yml" "$DORO_DIR/gateway/nginx.conf" \
           "$BLOG_DIR/.env" "$BLOG_DIR/docker-compose.prod.yml" \
           "$DORO_DIR/certbot/renew.sh" "$DORO_DIR/certbot/conf/renewal"; do
    [ -e "$f" ] && files+=("${f#$HOME/}")
  done
  crontab -l > "$dir/crontab.txt" 2>/dev/null || true
  docker ps --format '{{.Names}}\t{{.Image}}\t{{.Ports}}' > "$dir/containers.txt"
  tar czf "$dir/config.tgz" --ignore-failed-read -C "$HOME" "${files[@]}" 2>/dev/null
  log "  설정: 파일 ${#files[@]}개 -> $(du -h "$dir/config.tgz" | cut -f1)"

  # 5) 체크섬과 요약
  ( cd "$dir" && sha256sum -- * > SHA256SUMS )
  {
    echo "created=$(date +%FT%T%z)"
    echo "host=$(hostname)"
    echo "databases=$(list_databases | tr '\n' ' ')"
    echo "postgres_tables=$total_tables"
    echo "size=$(du -sh "$dir" | cut -f1)"
  } > "$dir/MANIFEST.txt"
  chmod -R go-rwx "$dir"
  mv "$dir" "$ROOT/daily/$stamp"
  trap - EXIT

  # 6) 주간/월간 세대 (하드링크라 추가 공간을 거의 쓰지 않는다)
  local week month
  week="$(date +%G-W%V)"; month="$(date +%Y-%m)"
  ls -1d "$ROOT"/weekly/*"_$week" >/dev/null 2>&1 || cp -al "$ROOT/daily/$stamp" "$ROOT/weekly/${stamp}_$week"
  ls -1d "$ROOT"/monthly/*"_$month" >/dev/null 2>&1 || cp -al "$ROOT/daily/$stamp" "$ROOT/monthly/${stamp}_$month"

  prune "$ROOT/daily" "$KEEP_DAILY"
  prune "$ROOT/weekly" "$KEEP_WEEKLY"
  prune "$ROOT/monthly" "$KEEP_MONTHLY"

  set_status result "OK"
  set_status last_success "$(date +%FT%T%z)"
  set_status last_backup_dir "$ROOT/daily/$stamp"
  set_status last_backup_size "$(du -sh "$ROOT/daily/$stamp" | cut -f1)"
  log "백업 완료: $ROOT/daily/$stamp ($(du -sh "$ROOT/daily/$stamp" | cut -f1))"

  offsite_copy
}

prune() { # prune <디렉터리> <보관 개수>
  local dir="$1" keep="$2" count
  count="$(ls -1d "$dir"/[0-9]* 2>/dev/null | wc -l || true)"
  if [ "$count" -gt "$keep" ]; then
    ls -1d "$dir"/[0-9]* | sort | head -n "$((count - keep))" | while read -r old; do
      rm -rf "$old"; log "  오래된 세대 삭제: $(basename "$old")"
    done
  fi
}

offsite_copy() {
  if [ -z "$OFFSITE_TARGETS" ]; then
    set_status offsite "NOT_CONFIGURED"
    log "오프사이트 복사: 설정되지 않음 (OFFSITE_TARGETS 비어 있음). 같은 디스크에만 보관 중이다."
    return 0
  fi
  # --delete 를 쓰지 않는다: 로컬 백업이 실수/랜섬웨어로 지워져도 오프사이트 사본은 남아야 한다.
  # 오래된 세대의 정리는 받는 쪽이 자체 보관 정책으로 한다. -H 는 주간/월간 하드링크를 보존한다.
  # 한 곳이 실패해도 나머지 대상은 계속 복사한다. 전체 상태(offsite)는 모든 곳이 성공해야 OK 이고, 곳별 상태는 offsite_<이름> 에 남긴다.
  local target name failed=""
  for target in $OFFSITE_TARGETS; do
    name="${target%%:*}"
    if rsync -aH --exclude '.lock' -e 'ssh -o BatchMode=yes -o ConnectTimeout=15' "$ROOT/" "$target/"; then
      set_status "offsite_$name" "OK $(date +%FT%T%z)"
      log "오프사이트 복사 완료: $target"
    else
      set_status "offsite_$name" "FAIL $(date +%FT%T%z)"
      failed="$failed $name"
      log "WARN: 오프사이트 복사 실패 ($target). 로컬 백업과 다른 대상은 정상이다."
    fi
  done
  if [ -z "$failed" ]; then
    set_status offsite "OK $(date +%FT%T%z)"
  else
    set_status offsite "FAIL $(date +%FT%T%z) (실패:${failed})"
  fi
}

# ------------------------------------------------------------------ verify-restore
do_verify_restore() {
  mkdir -p "$ROOT"
  exec 9>"$ROOT/.lock"
  flock -n 9 || die "다른 백업/검증이 실행 중이다"
  local backup; backup="$(latest_daily)"
  [ -n "$backup" ] || die "검증할 백업이 없다"
  log "== 복원 테스트: $backup =="
  ( cd "$backup" && sha256sum -c SHA256SUMS --quiet ) || die "체크섬 불일치: 백업 파일이 손상되었다"
  log "  체크섬 검증 통과"

  RESTORE_NAME="doro-restore-test-$$"
  trap 'rc=$?; docker rm -f "$RESTORE_NAME" >/dev/null 2>&1 || true; if [ $rc -ne 0 ]; then set_status last_restore_test "FAIL $(date +%FT%T%z)"; log "복원 테스트 실패 (exit $rc)"; fi' EXIT
  local pw; pw="$(openssl rand -hex 16)"
  docker run -d --name "$RESTORE_NAME" --network none -e POSTGRES_PASSWORD="$pw" --tmpfs /var/lib/postgresql/data "$TOOLBOX_IMAGE" >/dev/null
  for _ in $(seq 1 40); do docker exec "$RESTORE_NAME" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
  docker exec "$RESTORE_NAME" pg_isready -U postgres >/dev/null 2>&1 || die "임시 PostgreSQL 이 시작되지 않았다"

  local count_sql="select coalesce(sum((xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', schemaname, tablename), false, true, '')))[1]::text::bigint),0) from pg_tables where schemaname='public'"
  local dump db expected restored t rows_restored rows_live
  for dump in "$backup"/*.dump; do
    db="$(basename "$dump" .dump)"
    docker exec "$RESTORE_NAME" createdb -U postgres "$db"
    docker exec -i "$RESTORE_NAME" pg_restore -U postgres -d "$db" --no-owner --no-privileges --exit-on-error < "$dump" \
      || die "$db 복원 중 오류"
    # pg_restore --list 의 TABLE DATA 줄: "<id>; 0 <oid> TABLE DATA <schema> <table> <owner>"
    expected="$(docker exec -i "$RESTORE_NAME" pg_restore --list < "$dump" | grep 'TABLE DATA' | awk '{print $7}' | sort | tr '\n' ' ')"
    restored="$(echo "select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' order by 1" \
      | docker exec -i "$RESTORE_NAME" psql -U postgres -d "$db" -tA | tr '\n' ' ')"
    for t in $expected; do
      case " $restored " in *" $t "*) ;; *) die "$db: 복원된 DB 에 테이블 $t 가 없다" ;; esac
    done
    rows_restored="$(echo "$count_sql" | docker exec -i "$RESTORE_NAME" psql -U postgres -d "$db" -tA)"
    rows_live="$(echo "$count_sql" | pg_exec "psql -U \"\$POSTGRES_USER\" -d $db -tA" 2>/dev/null || echo '?')"
    log "  $db: 테이블 $(echo "$expected" | wc -w | tr -d ' ')개 복원 확인, 행 수 복원본=$rows_restored 현재=$rows_live"
  done
  docker rm -f "$RESTORE_NAME" >/dev/null

  # Redis RDB 무결성, MinIO/설정 아카이브 읽기
  docker run --rm -v "$backup":/b:ro "$REDIS_IMAGE" redis-check-rdb /b/redis-dump.rdb >/dev/null 2>&1 \
    || die "Redis RDB 무결성 검사 실패"
  log "  Redis RDB 무결성 통과"
  tar tzf "$backup/minio-data.tgz" >/dev/null || die "MinIO 아카이브를 읽을 수 없다"
  tar tzf "$backup/config.tgz" >/dev/null || die "설정 아카이브를 읽을 수 없다"
  log "  MinIO/설정 아카이브 읽기 통과"

  trap - EXIT
  set_status last_restore_test "OK $(date +%FT%T%z) ($(basename "$backup"))"
  log "복원 테스트 성공"
}

# ------------------------------------------------------------------ status
age_hours() { # STATUS 값에서 ISO 시각을 찾아 경과 시간(시)을 계산한다
  local iso; iso="$(printf '%s' "$1" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]+[+-][0-9]{4}' | head -1 || true)"
  [ -n "$iso" ] || { echo 99999; return; }
  echo $(( ( $(date +%s) - $(date -d "$iso" +%s 2>/dev/null || echo 0) ) / 3600 ))
}

do_status() {
  [ -f "$STATUS_FILE" ] || { echo "STATUS 파일이 없다: 백업이 한 번도 실행되지 않았다"; exit 2; }
  local rc=0 ok_age restore_age
  ok_age="$(age_hours "$(get_status last_success)")"
  restore_age="$(age_hours "$(get_status last_restore_test)")"
  cat "$STATUS_FILE"
  echo "---"
  echo "마지막 성공 백업: ${ok_age}시간 전"
  if [ "$(get_status result)" != "OK" ]; then echo "WARN: 마지막 실행이 실패했다"; rc=1; fi
  if [ "$ok_age" -gt "$STALE_HOURS" ]; then echo "WARN: 백업이 ${STALE_HOURS}시간보다 오래되었다"; rc=1; fi
  case "$(get_status last_restore_test)" in OK*) ;; *) echo "WARN: 복원 테스트가 성공한 기록이 없다"; rc=1 ;; esac
  if [ "$restore_age" -gt $((RESTORE_STALE_DAYS * 24)) ]; then echo "WARN: 복원 테스트가 ${RESTORE_STALE_DAYS}일보다 오래되었다"; rc=1; fi
  case "$(get_status offsite)" in
    OK*) if [ "$(age_hours "$(get_status offsite)")" -gt "$STALE_HOURS" ]; then echo "WARN: 오프사이트 사본이 ${STALE_HOURS}시간보다 오래되었다"; rc=1; fi ;;
    *)   echo "WARN: 오프사이트 사본이 없다 ($(get_status offsite)). 디스크 장애에 취약하다"; rc=1 ;;
  esac
  echo "보관: daily $(ls -1d "$ROOT"/daily/[0-9]* 2>/dev/null | wc -l)/$KEEP_DAILY, weekly $(ls -1d "$ROOT"/weekly/[0-9]* 2>/dev/null | wc -l)/$KEEP_WEEKLY, monthly $(ls -1d "$ROOT"/monthly/[0-9]* 2>/dev/null | wc -l)/$KEEP_MONTHLY"
  exit $rc
}

# docker exec -i 등이 호출자의 stdin(터미널, ssh 파이프)을 먹지 않도록 막는다. 필요한 곳은 명령마다 명시적으로 리다이렉트한다.
exec </dev/null

mkdir -p "$ROOT"
# cron 의 리다이렉트(>>)는 umask 022 로 로그를 만들 수 있으므로 권한을 조인다. (로그에는 비밀번호가 없지만 일관되게 600)
for f in "$LOG_FILE" "$ROOT"/crontab.before-*; do [ -f "$f" ] && chmod 600 "$f"; done
case "${1:-}" in
  backup)         do_backup ;;
  verify-restore) do_verify_restore ;;
  status)         do_status ;;
  *) echo "사용법: $0 backup | verify-restore | status" >&2; exit 2 ;;
esac
