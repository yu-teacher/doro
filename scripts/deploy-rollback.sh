#!/usr/bin/env bash
# 배포 실패 때 이전 버전으로 되돌리기 위한 이미지 스냅샷과 복원. 배포 작업(deploy.yml)이 쓴다.
#
#   scripts/deploy-rollback.sh snapshot   # 배포 전: 실행 중인 서비스 컨테이너의 이미지를 doro-rollback/<서비스>:prev 로 보존한다
#   scripts/deploy-rollback.sh restore    # 배포 실패 후: 이번 배포로 이미지가 바뀐 서비스만 이전 이미지로 다시 띄운다
#
# 동작 원리
#  - snapshot: `docker compose ps` 로 서비스별 실행 중인 컨테이너를 찾아 (서비스, 이미지 이름, 이미지 ID)를 상태 파일에 적고,
#    이미지에 doro-rollback/<서비스>:prev 태그를 붙인다. 태그가 붙어 있으면 새 빌드가 같은 이름을 가져가도 옛 이미지가 dangling 이 되지 않아
#    `docker image prune -f` 로 지워지지 않는다.
#  - restore: 지금 컨테이너의 이미지 ID 가 스냅샷과 다른 서비스만 골라 이미지 이름(compose 가 쓰는 이름)을 이전 이미지로 되돌리고
#    `docker compose up -d --no-build --no-deps` 로 그 서비스만 다시 만든다. DB·Redis 처럼 이미지가 안 바뀐 서비스는 건드리지 않는다.
#  - 한계: Flyway 마이그레이션은 되돌리지 않는다(앞으로만 간다). 새 마이그레이션이 이전 코드와 호환되지 않는 파괴적 변경이면 복원한 서비스가
#    기동하지 못할 수 있다 — 마이그레이션은 추가 위주(컬럼·테이블 추가)로 작성한다.
#  - 처음 배포되는 서비스(스냅샷에 없음)는 복원 대상이 아니다(그대로 둔다).
#
# 환경변수: COMPOSE_FILE / COMPOSE_PROJECT_NAME(docker compose 기본), ROLLBACK_STATE(상태 파일, 기본 .rollback-state), ROLLBACK_PREFIX(기본 doro-rollback),
#          ROLLBACK_HEALTH_TIMEOUT(복원 뒤 healthy 대기 초, 기본 120)
set -Eeuo pipefail

MODE="${1:-}"
STATE="${ROLLBACK_STATE:-.rollback-state}"
PREFIX="${ROLLBACK_PREFIX:-doro-rollback}"
HEALTH_TIMEOUT="${ROLLBACK_HEALTH_TIMEOUT:-120}"
log() { printf '[rollback %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

container_of() { docker compose ps -q "$1" 2>/dev/null | head -1 || true; }
image_id_of() { docker inspect -f '{{.Image}}' "$1" 2>/dev/null || true; }

snapshot() {
  : > "$STATE"
  local svc cid name id
  for svc in $(docker compose config --services); do
    cid="$(container_of "$svc")"
    if [ -z "$cid" ]; then log "$svc: 실행 중인 컨테이너가 없어 건너뜀"; continue; fi
    name="$(docker inspect -f '{{.Config.Image}}' "$cid")"
    id="$(image_id_of "$cid")"
    docker tag "$id" "$PREFIX/$svc:prev"
    printf '%s\t%s\t%s\n' "$svc" "$name" "$id" >> "$STATE"
    log "$svc: ${id:7:12} ($name) 보존"
  done
}

wait_healthy() { # 컨테이너 이름 또는 ID. healthcheck 가 없으면 running 이면 통과
  local cid="$1" i st
  for i in $(seq 1 "$((HEALTH_TIMEOUT / 3))"); do
    st="$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$cid" 2>/dev/null || true)"
    case "$st" in "running healthy"|"running -") return 0 ;; esac
    sleep 3
  done
  return 1
}

restore() {
  if [ ! -s "$STATE" ]; then log "스냅샷이 없어 복원할 것이 없다"; return 0; fi
  local changed=() svc name id cid cur
  while IFS=$'\t' read -r svc name id; do
    cid="$(container_of "$svc")"
    cur="none"; [ -z "$cid" ] || cur="$(image_id_of "$cid")"
    if [ "$cur" = "$id" ]; then log "$svc: 이전 이미지 그대로라 건드리지 않음"; continue; fi
    docker tag "$id" "$name"
    changed+=("$svc")
    log "$svc: 이미지를 ${id:7:12} 로 되돌린다(지금 ${cur:7:12})"
  done < "$STATE"
  if [ "${#changed[@]}" -eq 0 ]; then log "되돌릴 서비스가 없다"; return 0; fi
  docker compose up -d --no-build --no-deps "${changed[@]}"
  local bad=0
  for svc in "${changed[@]}"; do
    cid="$(container_of "$svc")"
    if [ -n "$cid" ] && wait_healthy "$cid"; then log "$svc: 복원됨(healthy)"; else log "$svc: 복원했지만 healthy 로 돌아오지 않았다 — docker logs 로 확인한다"; bad=1; fi
  done
  return "$bad"
}

case "$MODE" in
  snapshot) snapshot ;;
  restore) restore ;;
  *) echo "사용법: scripts/deploy-rollback.sh snapshot|restore" >&2; exit 2 ;;
esac
