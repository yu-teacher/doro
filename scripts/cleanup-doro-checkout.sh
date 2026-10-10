#!/usr/bin/env bash
# 미니(서버)에서 실행한다: ~/doro 의 낡은 소스 체크아웃(.git, auth, guard, web, docker-compose.yml 등)을 정리하고 "운영 설정 전용 폴더"로 만든다.
#
# 배경: 컨테이너가 쓰는 compose 위치는 러너 작업 폴더(~/actions-runner/_work/doro/doro)다. ~/doro 의 소스·compose 는 오래된 사본이라
# 여기서 docker compose 를 실행하면 낡은 설정으로 서비스를 덮어쓸 위험이 있다. 그런데 ~/doro 에는 아직 운영에 꼭 필요한 것이 있다:
#   gateway/nginx.conf(게이트웨이 컨테이너가 파일로 마운트), certbot/(인증서·갱신 스크립트, 마운트), cert/, .env(배포가 복사해 쓰는 원본, 알림 토큰)
# 그래서 이 폴더는 지우지 않고 필요한 것만 남긴다(경로를 바꾸지 않으므로 게이트웨이 컨테이너·크론·CI 를 건드리지 않는다).
#
#   ssh mini 'bash -s -- --dry-run' < scripts/cleanup-doro-checkout.sh    # 무엇을 지울지·확인만(기본)
#   ssh mini 'bash -s -- --apply'   < scripts/cleanup-doro-checkout.sh    # 보관본 저장 후 정리
#
# 안전장치: 컨테이너가 이 폴더의 소스를 쓰지 않는지, 남길 것이 모두 있는지, 지울 목록 밖의 낯선 항목이 없는지 확인한다(있으면 중단).
# 지우기 전에 서버에만 있던 커밋(git bundle)·작업 트리 변경(patch)·파일 목록을 ~/archive/ 에 보관한다(.env·인증서는 담지 않는다).
set -Eeuo pipefail

DIR="${DORO_DIR:-$HOME/doro}"
ARCHIVE_DIR="${ARCHIVE_DIR:-$HOME/archive}"
APPLY=false
for a in "$@"; do case "$a" in --apply) APPLY=true ;; --dry-run) APPLY=false ;; *) echo "알 수 없는 옵션: $a" >&2; exit 2 ;; esac; done
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

# 남길 것(운영에 필요) / 지울 것(낡은 소스 체크아웃). 이 두 목록에 없는 항목이 있으면 사람이 먼저 보도록 중단한다.
KEEP=(.env gateway certbot cert)
KEEP_GLOB=('.env.bak-*')   # 비밀 값이 들어 있는 백업: 이 스크립트는 건드리지 않는다(별도로 지운다)
REMOVE=(.git .gitignore .env.example AGENTS.md README.md auth guard sdk web gradle gradlew gradlew.bat settings.gradle docs init-db monitoring docker-compose.yml)

[ -d "$DIR" ] || die "$DIR 가 없다"
[ "$(cd "$DIR" && pwd)" != "/" ] && [ "$(basename "$(cd "$DIR" && pwd)")" = "doro" ] || die "DORO_DIR 가 ~/doro 가 아니다: $DIR"
command -v docker >/dev/null || die "docker 가 필요하다"

# 1) 컨테이너가 이 폴더의 "소스"를 쓰지 않는지(마운트는 gateway·certbot 만 허용, compose 작업 폴더가 아님)
bad=0
for n in $(docker ps -a --format '{{.Names}}'); do
  wd="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$n" 2>/dev/null || true)"
  if [ "$wd" = "$DIR" ]; then log "  X $n 의 compose 작업 폴더가 $DIR 이다"; bad=1; fi
  while read -r src; do
    [ -n "$src" ] || continue
    case "$src" in
      "$DIR/gateway/nginx.conf"|"$DIR/certbot/conf"|"$DIR/certbot/www") ;;
      "$DIR"|"$DIR"/*) log "  X $n 이 예상 밖 경로를 마운트: $src"; bad=1 ;;
    esac
  done < <(docker inspect -f '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' "$n" 2>/dev/null)
done
[ "$bad" = 0 ] || die "컨테이너가 $DIR 의 소스를 쓰고 있다. 정리하지 않는다"
log "컨테이너 점검 통과: $DIR 안에서는 gateway/nginx.conf, certbot/conf, certbot/www 만 마운트된다"

# 2) 남길 것이 모두 있는가, 낯선 항목이 없는가
for k in gateway/nginx.conf certbot/conf certbot/www certbot/renew.sh .env; do [ -e "$DIR/$k" ] || die "남겨야 할 $k 가 없다"; done
unknown=()
shopt -s dotglob nullglob
for p in "$DIR"/*; do
  name="$(basename "$p")"; known=false
  for k in "${KEEP[@]}" "${REMOVE[@]}"; do [ "$name" = "$k" ] && known=true; done
  for g in "${KEEP_GLOB[@]}"; do case "$name" in $g) known=true ;; esac; done
  [ "$known" = true ] || unknown+=("$name")
done
shopt -u dotglob nullglob
[ "${#unknown[@]}" = 0 ] || die "목록에 없는 항목이 있다(직접 확인 필요): ${unknown[*]}"
log "구성 점검 통과: 모든 항목이 남길 것/지울 것 목록에 있다"

log "지울 것(크기):"
total=0
for r in "${REMOVE[@]}"; do
  [ -e "$DIR/$r" ] || continue
  sz="$(du -sk "$DIR/$r" 2>/dev/null | cut -f1)"; total=$((total + sz)); printf '    %-18s %6s KB\n' "$r" "$sz"
done
log "합계 약 $((total / 1024)) MB. 남기는 것: ${KEEP[*]} ${KEEP_GLOB[*]}"
if [ -d "$DIR/.git" ]; then
  ahead="$(git -C "$DIR" rev-list --count 'origin/main..HEAD' 2>/dev/null || echo ?)"
  log "서버에만 있는 커밋 ${ahead}개, 작업 트리 변경 $(git -C "$DIR" status --short 2>/dev/null | wc -l | tr -d ' ')개 -> 보관본에 담는다"
fi
[ "$APPLY" = true ] || { log "--dry-run 이므로 여기서 종료한다(아무것도 바꾸지 않았다). 반영하려면 --apply"; exit 0; }

# 3) 보관본: 서버에만 있던 커밋·변경·목록. 비밀(.env·인증서)은 넣지 않는다.
TS="$(date +%Y%m%d-%H%M%S)"; OUT="$ARCHIVE_DIR/doro-checkout-$TS"
mkdir -p "$OUT"; chmod 700 "$ARCHIVE_DIR" "$OUT"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" bundle create "$OUT/server-only-commits.bundle" 'origin/main..HEAD' >/dev/null 2>&1 || log "경고: git bundle 을 만들지 못했다(서버에만 있는 커밋이 없을 수 있다)"
  git -C "$DIR" diff HEAD > "$OUT/worktree-changes.patch" 2>/dev/null || true
  git -C "$DIR" status --short > "$OUT/git-status.txt" 2>/dev/null || true
  git -C "$DIR" log --oneline -20 > "$OUT/git-log.txt" 2>/dev/null || true
fi
( cd "$DIR" && ls -la ) > "$OUT/ls-before.txt"
log "보관본: $OUT ($(du -sh "$OUT" | cut -f1))"

# 4) 지운다(목록의 이름만, 와일드카드 없음)
for r in "${REMOVE[@]}"; do
  [ -e "$DIR/$r" ] || continue
  rm -rf -- "${DIR:?}/$r" || die "$r 를 지우지 못했다(권한: sudo 가 필요할 수 있다)"
done
cat > "$DIR/README-운영설정폴더.md" <<'README'
# ~/doro 는 소스 체크아웃이 아니라 "운영 설정 전용 폴더"다 (정리일 기록은 INFRA.md)

여기에는 운영에 꼭 필요한 것만 있다. 소스와 docker-compose.yml 은 **없다**. 컨테이너가 쓰는 compose 는 러너 작업 폴더
(`~/actions-runner/_work/doro/doro`)에 있고, 코드는 GitHub 저장소가 원본이다. 여기서 `docker compose` 를 실행하지 않는다.

- `gateway/nginx.conf` : 게이트웨이 컨테이너(`doro-gateway`)가 파일로 마운트하는 **운영 설정**. 같은 inode 로 덮어쓰고 `nginx -t` 후 reload.
- `certbot/` : 인증서(`conf`)·검증 폴더(`www`)·갱신 스크립트(`renew.sh`, 월요일 04:00 크론). 게이트웨이가 마운트한다.
- `cert/` : 예전 인증서 폴더(root 소유).
- `.env` : 배포가 러너 작업 폴더로 복사해 쓰는 **원본 환경변수**(DB 비밀번호, 토큰 등). 배포 알림의 텔레그램 값도 여기에 있다.
README
log "정리 완료. 남은 항목:"; ls -A "$DIR" | sed 's/^/    /'

# 5) 확인: 게이트웨이가 그대로 서비스하는지
docker exec doro-gateway nginx -t >/dev/null 2>&1 || die "게이트웨이 설정 검증 실패(이 스크립트가 바꾼 것은 없다. 확인 필요)"
for u in / /login /blog/ /menu/ /party/ /games/; do
  code="$(curl -sk -o /dev/null -m 6 -w '%{http_code}' "https://127.0.0.1$u" || true)"
  [ "$code" = 200 ] && log "  OK   $u -> $code" || die "  FAIL $u -> ${code:-없음}"
done
log "완료. 보관본: $OUT"
