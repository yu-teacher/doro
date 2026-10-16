# 통합(E2E) 시험

가짜 IAM 이 아니라 **진짜 IAM(auth)·Guard·Postgres·Redis, 진짜 서비스(블로그 백엔드), 진짜 Chromium** 으로 "도로로 로그인" 흐름을 끝까지 시험한다.
단위·통합 시험(가짜 IAM)이 못 보는 것: 클라이언트 등록, redirect_uri 정확 일치, PKCE, `cid` 클레임, 쿠키 Path, 토큰 갱신, IAM 장애 때의 동작.

```bash
e2e/run.sh            # 격리 스택을 띄우고 시험한 뒤 모두 내린다(처음엔 이미지 빌드로 몇 분)
KEEP=1 e2e/run.sh     # 시험 후에도 스택을 남긴다(디버깅). 내릴 때: e2e/run.sh --down
```

필요: Docker, Java 25, Node 24, 형제 디렉터리 `../doro-blog`(`BLOG_DIR` 로 변경).

## 두 가지 모드
- **gateway(기본)**: 브라우저는 nginx(게이트웨이)로 접속하고, 로그인·세션·토큰 갱신은 별도 `doro-bff` 컨테이너가 맡는다. nginx 가 쿠키를 `Authorization: Bearer` 로 바꿔 블로그 백엔드에 넘긴다(블로그는 `BLOG_AUTH_MODE=gateway`). 운영 게이트웨이의 `auth_request` 규칙을 그대로 옮긴 설정은 `gateway/default.conf.template`.
- **embedded**: 블로그 안의 SDK BFF 를 쓰는 이전 방식. `E2E_BFF_MODE=embedded e2e/run.sh`. 공통 시나리오 9개를 같은 시험으로 확인한다(게이트웨이 전용 4개는 건너뜀).

## 구성
- `compose.e2e.yml`: 격리 스택(컨테이너 이름 `e2e-*`, 포트 5xxxx, 볼륨 없음). 개발·운영 스택과 겹치지 않는다. 설정값은 `e2e.env`(비밀 아님).
- 포털은 `vite` 개발 서버를 `VITE_DEV_API_TARGET` 로 이 IAM 에 붙여 띄운다(로그인 화면).
- `setup.mjs`: 부트스트랩 API 로 첫 최고 관리자 → OAuth 클라이언트(`doro-blog`) 등록 → 시험용 일반 계정. **비밀번호는 실행마다 무작위**이며 `.state/e2e.json`(권한 600)에만 있다.
- 블로그 백엔드는 `gradlew bootRun` 으로 같은 스택에 붙여 띄운다(액세스 토큰 수명 20초로 만료를 빨리 재현).

## 시나리오 (`tests/blog-bff.spec.ts`)
1. 로그인 → 세션 쿠키(HttpOnly) → 복귀 경로 → 새로고침 유지
2. 액세스 토큰이 만료돼도 진짜 IAM 에서 갱신해 같은 쿠키로 유지
3. **IAM 이 죽은 사이 토큰이 만료돼도 로그아웃되지 않고, 복구되면 같은 쿠키로 로그인 상태**
4. 로그인 CSRF: 다른 브라우저의 콜백은 거부
5. 실패한 콜백이 기존 세션을 지우지 않음
6. CSRF 헤더 없는 쿠키 요청 거부, 로그아웃
7. 로그아웃이 IAM 세션까지 폐기(IAM DB 직접 확인)
8. 서비스별 기기 3개(넘으면 가장 오래 안 쓴 기기만 로그아웃)
9. 계정이 정지되면 API 키가 막히고, 해제되면 같은 키가 다시 통한다
10. **(gateway 전용)** 서비스는 쿠키를 모른다: 같은 쿠키를 백엔드에 직접 보내면 익명
11. **(gateway 전용)** 쿠키가 있으면 클라이언트가 보낸 `Authorization` 은 BFF 토큰으로 덮어쓰인다
12. **(gateway 전용)** 쿠키로 상태를 바꾸는 서비스 API 도 CSRF 헤더가 없으면 게이트웨이에서 403
13. **(gateway 전용)** BFF 장애: 쿠키 없는 공개 요청은 정상, 쿠키 있는 요청만 503, 복구되면 같은 쿠키로 로그인 상태

## 알아 둘 점
- 로컬 개발 환경 전용이다. 운영 서버에는 접속하지 않는다.
- 포털 개발 서버의 프록시는 `/oauth2/consent` 를 IAM 이 아니라 포털 화면으로 처리한다(`web/vite.config.ts`, 운영 게이트웨이와 같은 규칙).
- CI 에 넣지는 않았다(이미지 빌드와 여러 프로세스가 필요해 느리다). 서비스 SDK 변경 전후에 손으로 돌린다.

## 영구 탈퇴 동기화 시험 (`run-deleted-sync.sh`)
"IAM 에서 영구 탈퇴 → 서비스가 자기 데이터를 정리" 를 **진짜 IAM·Guard·Postgres·S3 목과 진짜 도로 파티·도로 게임 백엔드**로 끝까지 확인한다. 브라우저는 쓰지 않는다(Playwright 불필요).

```bash
e2e/run-deleted-sync.sh          # 격리 스택 + 두 서비스를 띄워 시험하고 모두 내린다. 처음엔 이미지 빌드·bootRun 으로 10분 안팎
KEEP=1 e2e/run-deleted-sync.sh   # 시험 후에도 남긴다(디버깅). 내릴 때: E2E_DS_WORK=<출력된 임시 폴더> e2e/run-deleted-sync.sh --down
```

- 필요: Docker, Java 25, Node 24, 형제 저장소 `../../doro-party`·`../../doro-games`(`PARTY_DIR`·`GAMES_DIR` 로 변경). 두 서비스는 각 저장소의 **로컬 `origin/main`**(미리 `git fetch`)을 임시 git worktree 로 받아 `gradlew bootRun` 으로 띄우고, 끝나면 worktree 를 지운다. 원본 작업 디렉터리·브랜치는 건드리지 않는다. 서비스 빌드는 이 저장소의 SDK(`includeBuild('../Doro')`)를 쓴다.
- 로컬 전용이다(원격·운영 접속 없음). CI 에는 넣지 않았다(이미지 빌드와 여러 프로세스가 필요해 느리다). 포트: 파티 18086, 게임 18087(`E2E_PARTY_PORT`·`E2E_GAMES_PORT`).
- 시험용으로 IAM 의 탈퇴 유예를 1일·익명화 주기를 3초로, 서비스의 동기화 주기를 3초로 줄인다. 유예 경과는 **로컬 e2e Postgres 컨테이너**의 `users.deletion_requested_at` 을 직접 당겨서 만든다.
- 시나리오: 사용자 2명(탈퇴할 사람·남을 친구)이 OAuth(인가 코드+PKCE, 브라우저 없이 API 로) 토큰으로 파티에 지도·핀·사적 메모·사진·친구·공유·댓글을, 게임에 프로필·점수를 만든다 → IAM 탈퇴 요청(비밀번호 재입력; 기존 토큰 거부 확인) → 유예 앞당기기 → IAM 익명화 → 서비스 동기화를 기다려 확인한다.
- 확인하는 것: 파티/게임 사용자 행 익명화(`deleted-…`, "탈퇴한 사용자", `deleted_at`), 사적 메모·친구 관계·초대 링크·올린 사진(DB 행과 S3 파일) 삭제, 혼자 쓰던 지도 삭제와 공유·타인 기록이 있는 지도 유지(친구 공개는 꺼짐), 남은 친구의 API 접근 유지, Guard 튜플 정리, `iam_sync_state` 커서 전진, 로그에 ERROR 없음, 이후 주기가 아무것도 바꾸지 않음(멱등), 친구 데이터는 그대로(음성 대조).
- 비밀번호는 실행마다 무작위이며 `.state/deleted-sync.json`(권한 600)에만 있다. 서비스 로그는 `.state/party.log`·`.state/games.log`.
