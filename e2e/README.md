# 통합(E2E) 시험

가짜 IAM 이 아니라 **진짜 IAM(auth)·Guard·Postgres·Redis** 로 Doro 의 흐름을 끝까지 시험한다. 격리 스택은 `compose.e2e.yml`(컨테이너 이름 `e2e-*`, 포트 5xxxx, 볼륨 없음)이고 설정값은 `e2e.env`(비밀 아님)다.

- 로컬 개발 환경 전용이다. 운영 서버에는 접속하지 않는다. CI 에는 넣지 않았다(이미지 빌드와 여러 프로세스가 필요해 느리다).
- 브라우저 로그인을 서비스 대신 맡는 BFF·게이트웨이의 통합 시험(블로그 로그인 13개 시나리오)은 BFF 와 함께 **doro-gateway 저장소의 `e2e/`** 로 옮겼다(2026-11).

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

## 외부(제3자) 앱 시험 (`run-third-party.sh`)
"Doro 로 로그인"을 **자사 서비스 코드(SDK·BFF) 없이, 표준 OIDC 클라이언트 라이브러리(openid-client)만으로** 끝까지 붙일 수 있는지 확인한다. 구글·카카오 로그인을 붙이는 외부 개발자의 시점이다. 진짜 IAM·Guard·Postgres·Redis, 진짜 포털(개발 서버), 진짜 Chromium.

```bash
e2e/run-third-party.sh          # 격리 스택을 띄워 시험하고 모두 내린다. 처음엔 이미지 빌드로 몇 분
KEEP=1 e2e/run-third-party.sh   # 시험 후에도 남긴다(디버깅). 내릴 때: e2e/run-third-party.sh --down
PW_ARGS='-g 거부' e2e/run-third-party.sh   # 일부 시험만
```

- 발급자(issuer)는 운영처럼 **한 주소**(포털 개발 서버 `http://localhost:5180`, `/oauth2`·`/.well-known` 을 IAM 으로 프록시)다. 외부 앱은 127.0.0.1:19001 에서 뜬다(`E2E_EXT_APP_PORT`). 로컬 전용(원격·운영 접속 없음), CI 에는 넣지 않았다(이미지 빌드가 필요해 느리다).
- 클라이언트는 둘, 모두 `firstParty=false`(동의 화면을 거친다): `ext-confidential`(client_secret, scope openid profile email)과 `ext-public`(비밀키 없음, PKCE 만, scope openid). 비밀값은 실행마다 무작위이며 `.state/third-party.json`(권한 600)에만 있다.
- 확인하는 것: 발견 문서(발급자 주소 아래의 엔드포인트, S256 만, RS256, 공개키만 있는 JWKS) / 로그인→동의(등록된 앱 이름 표시)→코드→토큰→id_token 검증(서명·iss·aud·nonce)→userinfo→리프레시(회전, 범위 유지)→폐기 / scope 최소화(openid 만이면 이메일·이름이 id_token·userinfo 어디에도 없다) / 공개 클라이언트 PKCE / 사용자가 거부하면 앱으로 `error=access_denied&state` / 등록 안 된 redirect_uri·모르는 클라이언트는 리다이렉트하지 않음 / PKCE 없음·plain·`response_type=token`·허용 밖 scope 는 오류 리다이렉트 / 코드 1회용·verifier·client_secret·남의 코드 / 외부 앱 토큰은 userinfo 만 되고 IAM 의 다른 API 는 거부.
- 이 시험이 찾아서 고친 것: 동의 화면이 앱 이름 대신 client_id 를 보여 줌, 허용 scope 와 무관하게 userinfo 가 이메일·이름을 돌려줌(세션에 허용 scope 를 저장하고 액세스 토큰에 `scope` 를 실어 따르게 함), "취소"가 앱에 아무것도 알리지 않음(`POST /oauth2/deny`).
