#!/usr/bin/env bash
# 서비스별 MinIO 계정 분리: 지금은 블로그와 파티가 MinIO 의 루트 계정(MINIO_ROOT_USER)으로 접속한다. 서비스마다 자기 버킷만 쓸 수 있는
# 계정(blog_media, party_media)을 만들고, 그 계정으로만 접속하게 바꾼다. 한 서비스가 뚫려도 다른 서비스의 사진과 루트 권한은 얻지 못한다.
# (게임 그림 버킷 doro-games-assets 는 게이트웨이가 공개 읽기로만 내보내고 운영자가 루트로 올리므로 서비스 계정이 없다.)
# 운영 서버(mini)에서 실행한다. 비밀번호는 서버에서 만들어 .env 에만 쓰고 화면에 출력하지 않는다. MinIO 컨테이너 안의 mc 를 쓴다.
#
#   ssh mini 'bash -s -- --check'              < scripts/split-minio-accounts.sh   # 읽기 전용: 서비스별 계정·정책·접속 상태
#   ssh mini 'bash -s -- --apply blog'         < scripts/split-minio-accounts.sh   # 한 서비스만 전환(blog|party)
#   ssh mini 'bash -s -- --rollback blog'      < scripts/split-minio-accounts.sh   # .env 를 전환 전으로 되돌리고 그 서비스만 다시 만든다
#
# --apply <서비스> 가 하는 일 (이미 끝난 부분은 건너뛴다)
#   1) 그 서비스 버킷만 허용하는 정책(media-<서비스>)과 계정 <서비스>_media 를 만들고 정책을 붙인다(새 비밀번호)
#   2) 새 계정으로 확인: 자기 버킷에는 올리기·읽기·지우기가 되고, 다른 버킷(다른 서비스의 사진, 게임 그림)에는 접근이 거부된다
#   3) 서비스의 .env 에 MINIO_ACCESS_KEY/MINIO_SECRET_KEY 를 쓰고(백업 후) 그 서비스 컨테이너만 다시 만든다 → healthy 와 접속 계정 확인
#      healthy 가 되지 않으면 .env 를 되돌리고 다시 만든다. 계정은 그대로 둬도 루트가 계속 쓸 수 있어 안전하다.
# 서비스의 docker-compose.prod.yml 이 MINIO_ACCESS_KEY 를 받아야 한다(없으면 중단한다): 서비스 저장소의 compose 변경을 먼저 배포한다.
set -Eeuo pipefail

ACTION=""; SVC=""
while [ $# -gt 0 ]; do
  case "$1" in
    --check|--apply|--rollback) ACTION="$1" ;;
    blog|party) SVC="$1" ;;
    *) echo "사용법: bash -s -- --check | --apply <서비스> | --rollback <서비스>   (서비스: blog party)" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$ACTION" ] || { echo "사용법: bash -s -- --check | --apply <서비스> | --rollback <서비스>" >&2; exit 2; }
{ [ "$ACTION" = --check ] || [ -n "$SVC" ]; } || { echo "--apply/--rollback 은 서비스 이름이 필요하다" >&2; exit 2; }

MINIO="${MINIO_CONTAINER:-doro-minio}"
ROOT_ENV="${MINIO_ROOT_ENV:-$HOME/doro-blog/.env}"   # MinIO 루트 계정이 있는 .env (MinIO 컨테이너를 띄우는 compose 의 .env)
ALL="blog party"
OTHER_BUCKETS="doro-blog-media doro-party-media doro-games-assets"

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
healthy() { [ "$(docker inspect "$1" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
wait_healthy() { local i; for i in $(seq 1 "${HEALTH_TRIES:-60}"); do healthy "$1" && return 0; sleep "${HEALTH_SLEEP:-3}"; done; return 1; }

# 서비스별 설정
cfg() {
  case "$1" in
    blog)  BUCKET=doro-blog-media;  KEYUSER=blog_media;  POLICY=media-blog;  DIR="$HOME/doro-blog";  SERVICE=blog-backend; CONTAINER=doro-blog-backend ;;
    party) BUCKET=doro-party-media; KEYUSER=party_media; POLICY=media-party; DIR="$HOME/doro-party"; SERVICE=party-api;    CONTAINER=doro-party-api ;;
  esac
  ENV_FILE="$DIR/.env"; COMPOSE_FILE="$DIR/docker-compose.prod.yml"
}
recreate() { ( cd "$DIR" && docker compose -f docker-compose.prod.yml up -d --no-build --no-deps "$SERVICE" ) >/dev/null; }

# mc 호출. 접속 정보는 명령줄이 아니라 --env-file(프로세스 치환)로 넘겨 호스트 ps 에 비밀번호가 보이지 않게 한다.
ROOT_USER=""; ROOT_PASS=""
load_root() {
  [ -f "$ROOT_ENV" ] || die "MinIO 루트 계정이 있는 .env 가 없다: $ROOT_ENV"
  ROOT_USER="$(get_var "$ROOT_ENV" MINIO_ROOT_USER)"; ROOT_USER="${ROOT_USER:-doro_admin}"
  ROOT_PASS="$(get_var "$ROOT_ENV" MINIO_ROOT_PASSWORD)"
  [ -n "$ROOT_PASS" ] || die "$ROOT_ENV 에 MINIO_ROOT_PASSWORD 가 없다"
}
mc_as() { # mc_as <user> <pass> <mc 인자...>
  local u="$1" p="$2"; shift 2
  docker exec -i --env-file <(printf 'MC_HOST_local=http://%s:%s@127.0.0.1:9000\n' "$u" "$p") "$MINIO" mc --quiet "$@"
}
mc_root() { mc_as "$ROOT_USER" "$ROOT_PASS" "$@"; }

policy_json() { # 그 버킷 하나에 대한 객체·버킷 작업만 허용한다(다른 버킷, 관리 API, 계정 관리는 허용하지 않는다)
  cat <<JSON
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket","s3:ListBucketMultipartUploads","s3:CreateBucket","s3:GetBucketPolicy","s3:PutBucketPolicy"],"Resource":["arn:aws:s3:::$BUCKET"]},
 {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["arn:aws:s3:::$BUCKET/*"]}
]}
JSON
}
user_exists() { mc_root admin user info local "$KEYUSER" >/dev/null 2>&1; }
policy_attached() { mc_root admin user info local "$KEYUSER" 2>/dev/null | grep -q "$POLICY"; }
container_key() {
  docker inspect "$CONTAINER" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^MINIO_ACCESS_KEY=' | head -1 | cut -d= -f2-
}
compose_takes_var() { grep -q 'MINIO_ACCESS_KEY' "$COMPOSE_FILE" 2>/dev/null && grep -q 'MINIO_SECRET_KEY' "$COMPOSE_FILE" 2>/dev/null && grep -E 'MINIO_ACCESS_KEY:' "$COMPOSE_FILE" | grep -q '\${MINIO_ACCESS_KEY'; }

# 새 계정 확인: 자기 버킷은 올리기·읽기·지우기, 다른 버킷은 거부
verify_account() {
  local pw="$1" obj="split-check-$$-$(date +%s)" b
  echo ok | mc_as "$KEYUSER" "$pw" pipe "local/$BUCKET/$obj" >/dev/null 2>&1 || return 1
  [ "$(mc_as "$KEYUSER" "$pw" cat "local/$BUCKET/$obj" 2>/dev/null)" = ok ] || return 1
  mc_as "$KEYUSER" "$pw" rm "local/$BUCKET/$obj" >/dev/null 2>&1 || return 1
  mc_as "$KEYUSER" "$pw" ls "local/$BUCKET" >/dev/null 2>&1 || return 1
  for b in $OTHER_BUCKETS; do
    [ "$b" = "$BUCKET" ] && continue
    if mc_as "$KEYUSER" "$pw" ls "local/$b" >/dev/null 2>&1; then log "  [경고] $KEYUSER 가 다른 버킷 $b 에 접근할 수 있다"; return 1; fi
  done
  # 관리 API(계정 만들기 등)는 거부돼야 한다
  if mc_as "$KEYUSER" "$pw" admin user list local >/dev/null 2>&1; then log "  [경고] $KEYUSER 가 관리 API 를 쓸 수 있다"; return 1; fi
  return 0
}

describe_one() {
  cfg "$1"
  local ck; ck="$(container_key || true)"
  log "[$1] 버킷 $BUCKET | 컨테이너 접속 키: ${ck:-(없음: 루트 계정 사용)} | .env 의 MINIO_ACCESS_KEY: $([ -n "$(get_var "$ENV_FILE" MINIO_ACCESS_KEY)" ] && get_var "$ENV_FILE" MINIO_ACCESS_KEY || echo 없음)"
  log "[$1]   계정 $KEYUSER: $(user_exists && echo 있음 || echo 없음) | 정책 $POLICY 연결: $(policy_attached && echo 예 || echo 아니오) | compose 가 변수를 받음: $(compose_takes_var && echo 예 || echo 아니오)"
}

apply_one() {
  cfg "$1"
  [ -f "$ENV_FILE" ] || die ".env 가 없다: $ENV_FILE"
  [ -f "$COMPOSE_FILE" ] || die "compose 파일이 없다: $COMPOSE_FILE"
  compose_takes_var || die "$COMPOSE_FILE 이 MINIO_ACCESS_KEY/MINIO_SECRET_KEY 를 받지 않는다. 먼저 서비스 저장소의 compose 변경을 배포한다"
  healthy "$MINIO" || die "$MINIO 가 healthy 가 아니다. 먼저 정상으로 만든다"
  healthy "$CONTAINER" || die "$CONTAINER 가 healthy 가 아니다. 먼저 정상으로 만든다"
  load_root
  local cur_key cur_pw ts new_pw
  cur_key="$(get_var "$ENV_FILE" MINIO_ACCESS_KEY)"; cur_pw="$(get_var "$ENV_FILE" MINIO_SECRET_KEY)"
  if [ "$cur_key" = "$KEYUSER" ] && [ -n "$cur_pw" ] && [ "$(container_key || true)" = "$KEYUSER" ] && verify_account "$cur_pw"; then
    log "[$1] 이미 $KEYUSER 로 접속 중이다(할 일 없음)"; return 0
  fi
  ts="$(date +%Y%m%d-%H%M%S)"

  new_pw="$(openssl rand -hex 24)"
  policy_json | docker exec -i "$MINIO" sh -c "cat > /tmp/$POLICY.json"
  if mc_root admin policy info local "$POLICY" >/dev/null 2>&1; then mc_root admin policy remove local "$POLICY" >/dev/null 2>&1 || true; fi
  mc_root admin policy create local "$POLICY" "/tmp/$POLICY.json" >/dev/null || die "정책 $POLICY 를 만들지 못했다"
  docker exec "$MINIO" rm -f "/tmp/$POLICY.json" || true
  if user_exists; then
    mc_root admin user remove local "$KEYUSER" >/dev/null || die "기존 계정 $KEYUSER 를 지우지 못했다"
    log "[$1] 계정 $KEYUSER 는 이미 있어 새로 만들어 비밀번호를 바꿨다"
  fi
  mc_root admin user add local "$KEYUSER" "$new_pw" >/dev/null || die "계정 $KEYUSER 를 만들지 못했다"
  mc_root admin policy attach local "$POLICY" --user "$KEYUSER" >/dev/null || die "정책을 계정에 붙이지 못했다"
  log "[$1] 계정 $KEYUSER 와 정책 $POLICY(버킷 $BUCKET 만)를 만들었다"
  verify_account "$new_pw" || die "새 계정 확인에 실패했다. .env 는 바꾸지 않았다(루트 접속에는 영향이 없다)"
  log "[$1] 새 계정 확인: 자기 버킷 올리기·읽기·지우기 가능, 다른 버킷과 관리 API 는 거부"

  cp -p "$ENV_FILE" "$ENV_FILE.bak-miniosplit-$1-$ts"; log "[$1] .env 백업: $ENV_FILE.bak-miniosplit-$1-$ts"
  set_var "$ENV_FILE" MINIO_ACCESS_KEY "$KEYUSER"
  set_var "$ENV_FILE" MINIO_SECRET_KEY "$new_pw"
  recreate
  if wait_healthy "$CONTAINER" && [ "$(container_key || true)" = "$KEYUSER" ]; then
    log "[$1] $CONTAINER healthy, 접속 키 $KEYUSER"
    return 0
  fi
  log "[$1] $CONTAINER 가 healthy 로 돌아오지 않았다 — .env 를 되돌린다"
  cp -p "$ENV_FILE.bak-miniosplit-$1-$ts" "$ENV_FILE"; recreate || true
  wait_healthy "$CONTAINER" || log "되돌린 뒤에도 healthy 가 아니다. docker logs $CONTAINER 를 확인한다"
  die "되돌렸다(새 계정은 그대로 있다)"
}

rollback_one() {
  cfg "$1"
  local latest; latest="$(ls -1t "$ENV_FILE".bak-miniosplit-"$1"-* 2>/dev/null | head -1 || true)"
  [ -n "$latest" ] || die "[$1] 되돌릴 .env 백업이 없다"
  cp -p "$latest" "$ENV_FILE"; log "[$1] .env 를 $latest 로 되돌렸다"
  recreate
  wait_healthy "$CONTAINER" && log "[$1] $CONTAINER healthy, 접속 키 $(container_key || echo '(루트)')" || die "되돌린 뒤 $CONTAINER 가 healthy 가 아니다"
}

docker inspect "$MINIO" >/dev/null 2>&1 || die "컨테이너 $MINIO 가 없다"
load_root
case "$ACTION" in
  --check) for s in $ALL; do describe_one "$s"; done ;;
  --apply) apply_one "$SVC" ;;
  --rollback) rollback_one "$SVC" ;;
esac
