#!/usr/bin/env bash
# 서버(mini)에서 새 GitHub 저장소용 셀프호스티드 러너를 등록한다. 이미 있는 Doro 러너(~/actions-runner)의 설치 파일을 재사용한다.
# 등록 토큰은 저장소 Settings > Actions > Runners > New self-hosted runner 에서 발급한다 (1시간 안에 쓰고, 이 스크립트는 저장하지 않는다).
#
#   scripts/setup-github-runner.sh <owner/repo> <등록토큰> [러너이름]
#   예) scripts/setup-github-runner.sh yu-teacher/doro-blog AAAA... mini-blog
#
# 서비스 설치/시작은 sudo 가 필요해서 마지막에 명령만 안내한다. 완료 후 저장소 변수 CD_ENABLED=true 를 설정하면 배포가 켜진다.
set -Eeuo pipefail

REPO="${1:?사용법: $0 <owner/repo> <등록토큰> [러너이름]}"
TOKEN="${2:?등록 토큰이 필요하다}"
SLUG="${REPO##*/}"
NAME="${3:-mini-$SLUG}"
SRC="${RUNNER_TEMPLATE_DIR:-$HOME/actions-runner}"
DEST="$HOME/actions-runner-$SLUG"

TARBALL="$(ls "$SRC"/actions-runner-linux-x64-*.tar.gz 2>/dev/null | head -1 || true)"
[ -n "$TARBALL" ] || { echo "$SRC 에 러너 설치 파일(actions-runner-linux-x64-*.tar.gz)이 없다" >&2; exit 1; }
[ ! -e "$DEST" ] || { echo "$DEST 가 이미 있다. 이미 등록된 러너라면 건드리지 않는다." >&2; exit 1; }

mkdir -p "$DEST"
tar -xzf "$TARBALL" -C "$DEST"
cd "$DEST"
./config.sh --unattended --url "https://github.com/$REPO" --token "$TOKEN" --name "$NAME" --work _work --replace

cat <<MSG

러너 등록 완료: $NAME ($REPO), 위치 $DEST
다음 두 줄을 실행하면 부팅 후에도 자동으로 뜨는 서비스가 된다 (sudo 필요):

  cd $DEST && sudo ./svc.sh install $USER && sudo ./svc.sh start

그 다음 저장소 Settings > Secrets and variables > Actions > Variables 에 CD_ENABLED=true 를 추가하면 배포가 켜진다.
MSG
