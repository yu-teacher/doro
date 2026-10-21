#!/usr/bin/env bash
# scripts/split-minio-accounts.sh 시험: 임시 MinIO 컨테이너와 가짜 HOME, docker 셔심으로 실제 코드 경로를 그대로 돌린다.
# (운영 MinIO·서버에는 접근하지 않는다.)  사용: bash scripts/test-split-minio-accounts.sh
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
SCRIPT="$PWD/scripts/split-minio-accounts.sh"
IMAGE="${MINIO_TEST_IMAGE:-quay.io/minio/minio:latest}"
T="$(mktemp -d)"; C=splitminio-test
trap 'docker rm -f "$C" >/dev/null 2>&1 || true; rm -rf "$T"' EXIT
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf '  ok  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  FAIL %s\n' "$1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

ROOTPW="rootpw-$(openssl rand -hex 8)"
docker rm -f "$C" >/dev/null 2>&1 || true
docker run -d --name "$C" --platform "${MINIO_TEST_PLATFORM:-linux/amd64}" -e MINIO_ROOT_USER=doro_admin -e MINIO_ROOT_PASSWORD="$ROOTPW" "$IMAGE" server /data >/dev/null
rootmc() { docker exec -i --env-file <(printf 'MC_HOST_local=http://doro_admin:%s@127.0.0.1:9000\n' "$ROOTPW") "$C" mc --quiet "$@"; }
for _ in $(seq 1 40); do rootmc ls local >/dev/null 2>&1 && break; sleep 1; done
for b in doro-blog-media doro-party-media doro-games-assets; do rootmc mb "local/$b" >/dev/null; echo "seed-$b" | rootmc pipe "local/$b/seed.txt"; done

# 가짜 HOME + docker 셔심(compose 는 흉내, health 는 파일로 제어, 컨테이너 환경은 .env 에서 읽어 재현)
H="$T/home"; mkdir -p "$H/doro-party" "$H/doro-blog" "$T/bin"
printf 'MINIO_ROOT_USER=doro_admin\nMINIO_ROOT_PASSWORD=%s\nOTHER=keep\n' "$ROOTPW" > "$H/doro-blog/.env"
printf 'MINIO_ROOT_PASSWORD=%s\nOTHER=keep\n' "$ROOTPW" > "$H/doro-party/.env"
for s in blog party; do
  printf 'services:\n  x:\n    environment:\n      SERVER_ONLY: keep-%s\n      MINIO_ACCESS_KEY: ${MINIO_ROOT_USER:-doro_admin}\n      MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD:-doro_secret}\n      OTHER_LINE: stay\n' "$s" > "$H/doro-$s/docker-compose.prod.yml"
done
REAL_DOCKER="$(command -v docker)"
cat > "$T/bin/docker" <<SHIM
#!/usr/bin/env bash
REAL="$REAL_DOCKER"; H="$H"; T="$T"
if [ "\$1" = compose ]; then
  if [ -f "\$T/fail_after_recreate" ]; then
    if [ -f "\$T/unhealthy" ]; then rm -f "\$T/unhealthy"; else touch "\$T/unhealthy"; fi
  fi
  exit 0
fi
if [ "\$1" = inspect ] && [[ "\$*" == *Health.Status* ]]; then
  # 실제 doro-minio 처럼 헬스체크가 없다(상태 없음). 서비스 컨테이너만 healthy/unhealthy 를 흉내 낸다.
  if [ "\$2" = "$C" ]; then echo; elif [ -f "\$T/unhealthy" ]; then echo unhealthy; else echo healthy; fi; exit 0
fi
if [ "\$1" = inspect ] && [[ "\$*" == *Config.Env* ]]; then
  case "\$2" in
    doro-party-api) d=doro-party ;; doro-blog-backend) d=doro-blog ;; *) exec "\$REAL" "\$@" ;;
  esac
  k="\$(grep -m1 '^MINIO_ACCESS_KEY=' "\$H/\$d/.env" | cut -d= -f2-)"; echo "MINIO_ACCESS_KEY=\${k:-doro_admin}"; exit 0
fi
exec "\$REAL" "\$@"
SHIM
chmod +x "$T/bin/docker"
run() { PATH="$T/bin:$PATH" HEALTH_TRIES=2 HEALTH_SLEEP=1 HOME="$H" MINIO_CONTAINER="$C" MINIO_ROOT_ENV="$H/doro-blog/.env" bash "$SCRIPT" "$@" 2>&1; }
asmc() { docker exec -i --env-file <(printf 'MC_HOST_local=http://%s:%s@127.0.0.1:9000\n' "$1" "$2") "$C" mc --quiet "${@:3}" 2>&1; }

echo "== 사전 상태"
out="$(run --check)"; check "check 는 서비스 2개를 보여 준다" '[ "$(printf "%s\n" "$out" | grep -c "^\[.*\] \[")" -ge 2 ]'
check "check 는 비밀번호를 출력하지 않는다" '! printf "%s" "$out" | grep -qF "$ROOTPW"'
check "check 는 아무것도 바꾸지 않는다" '! grep -q "^MINIO_ACCESS_KEY=" "$H/doro-blog/.env"'

echo "== compose 가 변수를 받지 않으면 거부하고, --patch-compose 가 두 줄만 고친다"
out="$(run --apply blog || true)"; check "변수를 안 받는 compose 면 중단하고 --patch-compose 를 안내한다" 'printf "%s" "$out" | grep -q "받지 않는다" && printf "%s" "$out" | grep -q "patch-compose blog"'
cp "$H/doro-blog/docker-compose.prod.yml" "$T/compose.before"
out="$(run --patch-compose blog)"; echo "$out" | sed 's/^/    | /'
check "patch 후 MINIO 두 줄이 .env 변수를 먼저 쓴다" 'grep -q "MINIO_ACCESS_KEY: \${MINIO_ACCESS_KEY:-\${MINIO_ROOT_USER:-doro_admin}}" "$H/doro-blog/docker-compose.prod.yml" && grep -q "MINIO_SECRET_KEY: \${MINIO_SECRET_KEY:-\${MINIO_ROOT_PASSWORD" "$H/doro-blog/docker-compose.prod.yml"'
check "patch 는 다른 줄(서버 전용 값 포함)을 건드리지 않는다" '[ "$(diff "$T/compose.before" "$H/doro-blog/docker-compose.prod.yml" | grep -c "^[<>]")" = 4 ] && grep -q "SERVER_ONLY: keep-blog" "$H/doro-blog/docker-compose.prod.yml" && grep -q "OTHER_LINE: stay" "$H/doro-blog/docker-compose.prod.yml"'
check "patch 전 원본이 백업됐다" 'cmp -s "$T/compose.before" "$H/doro-blog/docker-compose.prod.yml.bak-miniosplit"'
out="$(run --patch-compose blog)"; check "patch 는 멱등이다" 'printf "%s" "$out" | grep -q "이미 MINIO_ACCESS_KEY 를 받는다"'
run --patch-compose party >/dev/null
printf 'services:\n  x:\n    environment:\n      A: 1\n' > "$H/doro-party/docker-compose.prod.yml.tmpcase"
cp "$H/doro-party/docker-compose.prod.yml" "$T/party.compose.keep"
cp "$H/doro-party/docker-compose.prod.yml.tmpcase" "$H/doro-party/docker-compose.prod.yml"
out="$(run --patch-compose party || true)"; check "고칠 줄이 없으면 중단한다" 'printf "%s" "$out" | grep -q "찾지 못했다"'
cp "$T/party.compose.keep" "$H/doro-party/docker-compose.prod.yml"; rm -f "$H/doro-party/docker-compose.prod.yml.tmpcase"

echo "== blog 전환"
out="$(run --apply blog)"; echo "$out" | sed 's/^/    | /'
bpw="$(grep '^MINIO_SECRET_KEY=' "$H/doro-blog/.env" | cut -d= -f2-)"
check ".env 에 새 키와 비밀번호가 쓰였고 다른 항목은 그대로다" 'grep -q "^MINIO_ACCESS_KEY=blog_media$" "$H/doro-blog/.env" && [ ${#bpw} -ge 40 ] && grep -q "^OTHER=keep$" "$H/doro-blog/.env"'
check ".env 파일 권한이 600 이다" '[ "$(stat -c %a "$H/doro-blog/.env" 2>/dev/null || stat -f %Lp "$H/doro-blog/.env")" = 600 ]'
check "출력에 새 비밀번호·루트 비밀번호가 없다" '! printf "%s" "$out" | grep -qF "$bpw" && ! printf "%s" "$out" | grep -qF "$ROOTPW"'
check "blog_media 는 자기 버킷에 올리고 읽고 지운다" 'echo hi | asmc blog_media "$bpw" pipe local/doro-blog-media/a.txt >/dev/null && [ "$(asmc blog_media "$bpw" cat local/doro-blog-media/a.txt)" = hi ] && asmc blog_media "$bpw" rm local/doro-blog-media/a.txt >/dev/null'
check "blog_media 는 서비스가 기동 때 하는 버킷 정책 설정이 된다" 'asmc blog_media "$bpw" anonymous set download local/doro-blog-media >/dev/null'
check "blog_media 는 파티 버킷을 읽을 수 없다" '! asmc blog_media "$bpw" cat local/doro-party-media/seed.txt >/dev/null'
check "blog_media 는 게임 그림 버킷에 쓸 수 없다" '! (echo x | asmc blog_media "$bpw" pipe local/doro-games-assets/x.txt >/dev/null)'
check "blog_media 는 관리 API 를 못 쓴다" '! asmc blog_media "$bpw" admin user list local >/dev/null'
check "루트는 여전히 모든 버킷을 쓴다(백업·운영)" 'rootmc cat local/doro-blog-media/seed.txt >/dev/null && rootmc cat local/doro-party-media/seed.txt >/dev/null'

echo "== 멱등"
out="$(run --apply blog)"; check "다시 실행하면 할 일 없음" 'printf "%s" "$out" | grep -q "이미 blog_media 로 접속 중"'

echo "== 비밀번호 교체(--rotate)"
oldpw="$bpw"
out="$(run --rotate blog)"; echo "$out" | sed 's/^/    | /'
bpw="$(grep '^MINIO_SECRET_KEY=' "$H/doro-blog/.env" | cut -d= -f2-)"
check "rotate 후 비밀번호가 바뀌었다" '[ "$bpw" != "$oldpw" ] && [ ${#bpw} -ge 40 ]'
check "옛 비밀번호는 더 이상 안 된다" '! asmc blog_media "$oldpw" ls local/doro-blog-media >/dev/null'
check "새 비밀번호로 자기 버킷을 쓴다" 'asmc blog_media "$bpw" ls local/doro-blog-media >/dev/null'
check "rotate 출력에 비밀번호가 없다" '! printf "%s" "$out" | grep -qF "$bpw" && ! printf "%s" "$out" | grep -qF "$oldpw"'
check "rotate 는 다른 .env 항목을 건드리지 않는다" 'grep -q "^OTHER=keep$" "$H/doro-blog/.env" && grep -q "^MINIO_ACCESS_KEY=blog_media$" "$H/doro-blog/.env"'
out="$(run --apply blog)"; check "rotate 뒤 --apply 는 다시 할 일 없음" 'printf "%s" "$out" | grep -q "이미 blog_media 로 접속 중"'

echo "== 교체 중 서비스가 안 뜨면 새 비밀번호를 그대로 둔다(옛 비밀번호는 이미 무효)"
prevpw="$bpw"
touch "$T/fail_after_recreate"
out="$(run --rotate blog || true)"
rm -f "$T/fail_after_recreate" "$T/unhealthy"
newpw="$(grep '^MINIO_SECRET_KEY=' "$H/doro-blog/.env" | cut -d= -f2-)"
check "실패해도 .env 에는 새 비밀번호가 있고 그 비밀번호가 유효하다" '[ "$newpw" != "$prevpw" ] && asmc blog_media "$newpw" ls local/doro-blog-media >/dev/null'
check "되돌렸다고 하지 않고 이유를 알린다" 'printf "%s" "$out" | grep -q "되돌리지 않음"'
bpw="$newpw"; oldpw="$prevpw"

echo "== party 전환 후 격리"
run --apply party >/dev/null
ppw="$(grep '^MINIO_SECRET_KEY=' "$H/doro-party/.env" | cut -d= -f2-)"
check "party_media 는 자기 버킷만 쓴다" 'echo p | asmc party_media "$ppw" pipe local/doro-party-media/p.txt >/dev/null && asmc party_media "$ppw" rm local/doro-party-media/p.txt >/dev/null'
check "party_media 는 블로그 버킷을 읽을 수 없다" '! asmc party_media "$ppw" cat local/doro-blog-media/seed.txt >/dev/null'
check "blog_media 는 여전히 동작한다" 'asmc blog_media "$bpw" ls local/doro-blog-media >/dev/null'

echo "== 재생성 뒤 healthy 가 안 되면 .env 를 되돌린다"
cp -p "$H/doro-party/.env" "$T/party.env.keep"
touch "$T/fail_after_recreate"
out="$(run --apply party || true)"
rm -f "$T/fail_after_recreate" "$T/unhealthy"
check "이미 전환된 서비스는 건드리지 않는다(할 일 없음)" 'printf "%s" "$out" | grep -q "이미 party_media 로 접속 중"'
cp -p "$T/party.env.keep" "$H/doro-party/.env"
sed -i.bak '/^MINIO_ACCESS_KEY=/d;/^MINIO_SECRET_KEY=/d' "$H/doro-party/.env"; rm -f "$H/doro-party/.env.bak"
before="$(cat "$H/doro-party/.env")"
touch "$T/fail_after_recreate"
out="$(run --apply party || true)"
rm -f "$T/fail_after_recreate" "$T/unhealthy"
check "되돌리고 중단했다고 알린다" 'printf "%s" "$out" | grep -q "되돌렸다"'
check ".env 가 전환 전 내용이다" '[ "$(cat "$H/doro-party/.env")" = "$before" ]'

echo "== 되돌리기"
out="$(run --rollback blog)"
check "rollback 이 .env 를 직전 상태(교체 전 비밀번호)로 돌린다" '[ "$(grep "^MINIO_SECRET_KEY=" "$H/doro-blog/.env" | cut -d= -f2-)" = "$oldpw" ]'

echo; echo "통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ] && echo "모두 통과" || exit 1
