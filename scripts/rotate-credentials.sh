#!/usr/bin/env bash
# 운영 서버(mini)의 PostgreSQL / MinIO 기본 비밀번호를 새 무작위 값으로 회전한다.
#
# 서버에서 실행한다:
#   ssh mini 'bash -s'               < scripts/rotate-credentials.sh   # 사전 점검만 (아무것도 바꾸지 않음)
#   ssh mini 'bash -s -- --apply'    < scripts/rotate-credentials.sh   # 실제 회전
#
# 하는 일 (--apply):
#   1) 전체 DB 덤프와 .env 백업  2) 새 비밀번호 생성(출력하지 않음)  3) PostgreSQL 사용자 비밀번호 변경
#   4) ~/doro/.env, ~/doro-blog/.env 갱신  5) Doro 스택 -> blog/MinIO 순서로 재기동  6) 검증
# 3) 이후 단계에서 실패하면 .env 와 DB 비밀번호를 자동으로 원복하고 재기동한다.
set -Eeuo pipefail

APPLY=false
[ "${1:-}" = "--apply" ] && APPLY=true

DORO_DIR="${DORO_DIR:-$HOME/doro}"
BLOG_DIR="${BLOG_DIR:-$HOME/doro-blog}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner/_work/doro/doro}"
PG_CONTAINER="${PG_CONTAINER:-doro-postgres}"
PG_USER="${PG_USER:-doro_admin}"
DOCKER_NETWORK="${DOCKER_NETWORK:-doro_doro-network}"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/backups}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }

wait_healthy() {
  local name="$1" tries="${2:-40}"
  for _ in $(seq 1 "$tries"); do healthy "$name" && return 0; sleep 3; done
  return 1
}

# 네트워크로 접속했을 때 특정 비밀번호가 받아들여지는지 확인한다. (비밀번호는 argv 가 아닌 환경변수로 전달)
pg_accepts() {
  PGPASSWORD="$1" docker run --rm --network "$DOCKER_NETWORK" -e PGPASSWORD postgres:16-alpine \
    psql -h postgres -U "$PG_USER" -d postgres -tAc 'select 1' >/dev/null 2>&1
}

# ---------------------------------------------------------------- 사전 점검 (읽기 전용)
log "== 사전 점검 =="
for c in doro-postgres doro-redis doro-auth-api doro-guard-api doro-blog-backend doro-minio; do
  docker inspect "$c" >/dev/null 2>&1 || die "컨테이너 $c 가 없다"
done
for f in "$DORO_DIR/.env" "$BLOG_DIR/.env" "$BLOG_DIR/docker-compose.prod.yml" "$RUNNER_DIR/docker-compose.yml"; do
  [ -f "$f" ] || die "파일이 없다: $f"
done
for c in doro-postgres doro-auth-api doro-guard-api doro-blog-backend; do
  healthy "$c" || die "$c 가 healthy 가 아니다. 회전 전에 먼저 정상화한다"
done
OLD_PG="$(grep '^POSTGRES_PASSWORD=' "$DORO_DIR/.env" | cut -d= -f2-)"
[ -n "$OLD_PG" ] || die "$DORO_DIR/.env 에 POSTGRES_PASSWORD 가 없다"
pg_accepts "$OLD_PG" || die ".env 의 현재 비밀번호로 DB 접속이 되지 않는다. 상태를 먼저 확인한다"
[ "$(df --output=avail -BM "$HOME" | tail -1 | tr -dc 0-9)" -gt 2000 ] || die "디스크 여유가 2GB 미만이다"
log "사전 점검 통과 (서비스 healthy, 현재 비밀번호로 DB 접속 가능)"

if [ "$APPLY" != true ]; then
  log "dry-run 이므로 여기서 종료한다. 실제로 회전하려면 --apply 로 다시 실행한다."
  exit 0
fi

# ---------------------------------------------------------------- 백업
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP="$BACKUP_ROOT/pre-credential-rotation-$TS"
mkdir -p "$BACKUP"; chmod 700 "$BACKUP"
log "== 1. 백업: $BACKUP =="
DBS="$(echo "select datname from pg_database where datname not in ('postgres','template0','template1') order by 1" \
  | docker exec -i "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d postgres -tA')"
for DB in $DBS; do
  docker exec "$PG_CONTAINER" sh -c "pg_dump -U \"\$POSTGRES_USER\" -Fc $DB" > "$BACKUP/$DB.dump"
  N="$(docker exec -i "$PG_CONTAINER" pg_restore --list < "$BACKUP/$DB.dump" | grep -c 'TABLE DATA' || true)"
  [ "$N" -gt 0 ] || die "$DB 덤프 검증 실패"
  log "  $DB: $(du -h "$BACKUP/$DB.dump" | cut -f1), 복원 가능한 테이블 데이터 $N개"
done
cp -p "$DORO_DIR/.env" "$BACKUP/doro.env.old"
cp -p "$BLOG_DIR/.env" "$BACKUP/doro-blog.env.old"
cp -p "$BLOG_DIR/docker-compose.prod.yml" "$BACKUP/docker-compose.prod.yml"
chmod 600 "$BACKUP"/*.dump "$BACKUP"/*.env.old

# ---------------------------------------------------------------- 롤백 준비
# die(exit) 로 끝나는 경로도 잡아야 하므로 ERR 이 아니라 EXIT 트랩을 쓴다.
ROTATION_STARTED=false
COMPLETED=false
rollback_on_failure() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$ROTATION_STARTED" = true ] && [ "$COMPLETED" != true ]; then
    trap - EXIT
    log "!! 실패 (exit $code). 자동 롤백한다"
    cp -p "$BACKUP/doro.env.old" "$DORO_DIR/.env" || true
    cp -p "$BACKUP/doro-blog.env.old" "$BLOG_DIR/.env" || true
    printf "ALTER USER %s PASSWORD '%s';\n" "$PG_USER" "$OLD_PG" \
      | docker exec -i "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -q' || true
    ( cd "$RUNNER_DIR" && cp "$DORO_DIR/.env" .env && chmod 600 .env && docker compose up -d --no-build ) || true
    ( cd "$BLOG_DIR" && docker compose -f docker-compose.prod.yml up -d --no-build minio blog-backend ) || true
    log "롤백 완료. 백업: $BACKUP"
    exit "$code"
  fi
}
trap rollback_on_failure EXIT

# ---------------------------------------------------------------- 새 비밀번호
new_secret() { openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-32; }
NEW_PG="$(new_secret)"
NEW_MINIO="$(new_secret)"
[ "${#NEW_PG}" -eq 32 ] && [ "${#NEW_MINIO}" -eq 32 ] || die "비밀번호 생성 실패"
[ "$NEW_PG" != "$OLD_PG" ] || die "새 비밀번호가 기존과 같다"
log "== 2. 새 비밀번호 생성 (출력하지 않음) =="

# ---------------------------------------------------------------- DB 비밀번호 변경 + .env 갱신
ROTATION_STARTED=true
log "== 3. PostgreSQL 사용자 비밀번호 변경 =="
printf "ALTER USER %s PASSWORD '%s';\n" "$PG_USER" "$NEW_PG" \
  | docker exec -i "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -q'

log "== 4. .env 갱신 =="
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$NEW_PG|" "$DORO_DIR/.env"
BLOG_ENV="$BLOG_DIR/.env"
if grep -q '^DB_PASSWORD=' "$BLOG_ENV"; then sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$NEW_PG|" "$BLOG_ENV"; else printf 'DB_PASSWORD=%s\n' "$NEW_PG" >> "$BLOG_ENV"; fi
if grep -q '^MINIO_ROOT_PASSWORD=' "$BLOG_ENV"; then sed -i "s|^MINIO_ROOT_PASSWORD=.*|MINIO_ROOT_PASSWORD=$NEW_MINIO|" "$BLOG_ENV"; else printf 'MINIO_ROOT_PASSWORD=%s\n' "$NEW_MINIO" >> "$BLOG_ENV"; fi
chmod 600 "$DORO_DIR/.env" "$BLOG_ENV"

# ---------------------------------------------------------------- 재기동
log "== 5-a. Doro 스택 재기동 (postgres, auth, guard) =="
( cd "$RUNNER_DIR" && cp "$DORO_DIR/.env" .env && chmod 600 .env && docker compose up -d --no-build )
wait_healthy doro-postgres  || die "postgres 가 healthy 로 돌아오지 않았다"
wait_healthy doro-auth-api  || die "auth-api 가 healthy 로 돌아오지 않았다"
wait_healthy doro-guard-api || die "guard-api 가 healthy 로 돌아오지 않았다"

log "== 5-b. blog / MinIO 재기동 =="
( cd "$BLOG_DIR" && docker compose -f docker-compose.prod.yml up -d --no-build minio blog-backend )
wait_healthy doro-blog-backend || die "blog-backend 가 healthy 로 돌아오지 않았다"

# ---------------------------------------------------------------- 검증
log "== 6. 검증 =="
pg_accepts "$NEW_PG" || die "새 비밀번호로 DB 접속이 되지 않는다"
if pg_accepts "doro_secret" || pg_accepts "$OLD_PG"; then die "기존 비밀번호가 여전히 통한다"; fi
log "  PostgreSQL: 새 비밀번호 허용, 기존 비밀번호 거부"
for pair in "auth:8080" "guard:8081" "blog:8082"; do
  code="$(curl -s -m 8 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${pair#*:}/actuator/health")"
  [ "$code" = "200" ] || die "${pair%%:*} 헬스 ${code}"
  log "  ${pair%%:*} health 200"
done
code="$(curl -sk -m 8 -o /dev/null -w '%{http_code}' https://127.0.0.1/media/)"
[ "$code" = "200" ] || die "게이트웨이 /media/ 응답 ${code}"
log "  gateway /media/ 200"

COMPLETED=true
log "회전 완료. 롤백이 필요하면 $BACKUP 의 *.env.old 로 되돌리고 ALTER USER 로 기존 비밀번호를 복구한다."
log "다음: 문서/메모리의 '기본 비밀번호 사용 중' 항목을 해결됨으로 갱신한다."
