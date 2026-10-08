#!/usr/bin/env bash
# 다른 서버(예: notebook)에 GitHub 셀프호스티드 러너를 등록한다. 두 서버에 ssh 로 접속할 수 있는 곳(예: Mac)에서 실행한다.
# 설치 파일은 이미 쓰고 있는 mini 러너의 tar.gz 를 그대로 가져와 같은 버전을 쓴다(새로 내려받지 않는다).
# 등록 토큰은 저장소 Settings > Actions > Runners > New self-hosted runner 에서 발급한다(1시간 안에 쓰고, 이 스크립트는 저장하지 않는다).
#
#   scripts/setup-remote-runner.sh <호스트> <owner/repo> <등록토큰> [러너이름] [라벨]
#   예) scripts/setup-remote-runner.sh notebook yu-teacher/Doro AAAA... notebook-ci notebook
#
# 러너에 CPU/메모리 한도(CI_CPUS, CI_MEMORY)를 알리는 .env 도 만든다(ci-test.sh 가 읽는다). 값은 RUNNER_CPUS / RUNNER_MEMORY 로 바꾼다.
# 서비스 설치/시작은 sudo 가 필요해서 마지막에 명령만 안내한다.
set -Eeuo pipefail

HOST="${1:?사용법: $0 <호스트> <owner/repo> <등록토큰> [러너이름] [라벨]}"
REPO="${2:?owner/repo 가 필요하다}"
TOKEN="${3:?등록 토큰이 필요하다}"
NAME="${4:-$HOST-ci}"
LABEL="${5:-$HOST}"
MINI="${MINI_HOST:-mini}"
SLUG="${REPO##*/}"
DEST="actions-runner-$SLUG"           # 대상 서버의 홈 아래 디렉터리
CPUS="${RUNNER_CPUS:-3}"
MEMORY="${RUNNER_MEMORY:-12g}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10)
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

log "== 사전 점검 =="
ssh "${SSH_OPTS[@]}" "$HOST" 'command -v docker >/dev/null && command -v git >/dev/null && id -nG | tr " " "\n" | grep -qx docker' \
  || die "$HOST 에 docker/git 이 없거나 현재 사용자가 docker 그룹이 아니다"
ssh "${SSH_OPTS[@]}" "$HOST" "test ! -e ~/$DEST" || die "$HOST:~/$DEST 가 이미 있다. 이미 등록된 러너라면 건드리지 않는다."
TARBALL="$(ssh "${SSH_OPTS[@]}" "$MINI" 'ls ~/actions-runner/actions-runner-linux-x64-*.tar.gz | head -1')"
[ -n "$TARBALL" ] || die "$MINI 에서 러너 설치 파일을 찾지 못했다"
log "  설치 파일: $MINI:$TARBALL"

log "== 설치 파일 복사 ($MINI -> $HOST) =="
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
scp -q "${SSH_OPTS[@]}" "$MINI:$TARBALL" "$TMP/runner.tgz"
SUM_LOCAL="$(shasum -a 256 "$TMP/runner.tgz" | awk '{print $1}')"
scp -q "${SSH_OPTS[@]}" "$TMP/runner.tgz" "$HOST:/tmp/runner-$SLUG.tgz"
SUM_REMOTE="$(ssh "${SSH_OPTS[@]}" "$HOST" "sha256sum /tmp/runner-$SLUG.tgz | cut -d' ' -f1")"
[ "$SUM_LOCAL" = "$SUM_REMOTE" ] || die "복사한 파일의 체크섬이 다르다"

log "== 등록 =="
# 원격 스크립트는 파일로 보내고, 표준입력은 토큰 전달에만 쓴다(명령 인자로 주면 프로세스 목록에 보인다).
cat > "$TMP/remote-setup.sh" <<'REMOTE'
set -Eeuo pipefail
dest="$1"; repo="$2"; name="$3"; label="$4"; cpus="$5"; memory="$6"; slug="$7"
read -r token
mkdir -p "$HOME/$dest"
tar -xzf "/tmp/runner-$slug.tgz" -C "$HOME/$dest"
rm -f "/tmp/runner-$slug.tgz"
cd "$HOME/$dest"
./config.sh --unattended --url "https://github.com/$repo" --token "$token" --name "$name" --labels "$label" --work _work --replace
# ci-test.sh 가 읽는 한도. 노트북은 운영 서비스가 없으니 mini 보다 넉넉하게 준다.
printf 'CI_CPUS=%s\nCI_MEMORY=%s\n' "$cpus" "$memory" >> .env
REMOTE
scp -q "${SSH_OPTS[@]}" "$TMP/remote-setup.sh" "$HOST:/tmp/remote-setup-$SLUG.sh"
printf '%s\n' "$TOKEN" | ssh "${SSH_OPTS[@]}" "$HOST" "bash /tmp/remote-setup-$SLUG.sh '$DEST' '$REPO' '$NAME' '$LABEL' '$CPUS' '$MEMORY' '$SLUG'; rc=\$?; rm -f /tmp/remote-setup-$SLUG.sh; exit \$rc"

cat <<MSG

러너 등록 완료: $NAME ($REPO, 라벨 self-hosted,$LABEL), 위치 $HOST:~/$DEST
부팅 후에도 자동으로 뜨는 서비스로 만들려면 아래를 실행한다(sudo 필요):

  ssh -t $HOST 'cd ~/$DEST && sudo ./svc.sh install \$USER && sudo ./svc.sh start'

그 다음 저장소 Settings > Secrets and variables > Actions > Variables 에
  TEST_RUNS_ON = ["self-hosted","$LABEL"]
를 추가하면 테스트 작업이 이 러너에서 실행된다. 되돌리려면 변수를 지운다(기본값은 mini 러너).
MSG
