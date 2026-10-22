# Doro 인증·인가 연동 가이드 (에이전트용)

> **이 문서의 목적**: Doro IAM(인증) / Doro Guard(인가) / Doro SDK 를 사용하는 프로젝트에서 작업하는 AI 에이전트가, **다른 문서를 믿지 않고도** 실제 동작에 맞게 연동하도록 돕는다.
>
> **왜 필요한가**: 사용자 문서(`USER_GUIDE.md`)와 README 는 코드 변경을 따라가지 못해 어긋난 적이 많다. 이 문서는 **소스를 직접 읽고** 검증한 사실만 적었다. 이 문서와 코드가 다르면 **코드가 맞다.**

## 0. 출처와 신뢰 범위

| 항목 | 내용 |
|---|---|
| 검증일 | 2026-10-20 (직전 전면 검증 2026-10-02). 같은 날 2차로 `[미검증]` 항목을 코드·테스트·스크립트 본문 읽기로 해소했다(§14 의 두 번째 2026-10-20 항목) |
| 검증 대상 | `Doro` 저장소 커밋 **`bb03743`** (`git -C Doro rev-parse --short HEAD`). 직전 검증 커밋 `12d6582` 부터의 변경을 `git diff 12d6582..HEAD` 로 훑고, 바뀐 코드(`auth`, `guard`, `sdk`, `bff`, `gateway`, `docker-compose.yml`, `.env.example`, `.github/workflows`, `scripts`)를 직접 읽어 본문을 고쳤다. 검증 시점에 추적 파일의 working tree 변경은 없었다(추적되지 않는 설계 문서 `docs/NOTIFICATION_SERVICE_DESIGN.md` 만 있음). |
| 변경 없음으로 확인한 부분 | `12d6582` 이후 diff 가 **없는** 것은 다시 읽지 않고 직전 검증을 유지했다: Guard 엔진·DSL 파서·스키마 서비스·`schema.doro`(`guard/.../core`, `domain`), SDK 의 `JwksKeyProvider` 캐시 동작(미지 `kid` 재확인만 추가), `DoroGuardClient` 의 호출 방식, IAM 의 JWT 클레임·킬스위치·CORS 허용 Origin 기본값(노출 헤더 `X-Total-Count` 추가만 있음) |
| 참조 구현 | `<워크스페이스>/doro-blog`, `doro-party`, `doro-games`(SDK 를 쓰는 서브 서비스). **읽은 것은 각 서비스 `application.yaml` 의 `*.auth.mode` 기본값(`embedded`)과 그 스위치(`ConditionalOnEmbeddedBff`, 각 서비스 `*BffConfig`·`BffSessionController`·BFF 세션 저장소·`V*__bff_sessions.sql`), doro-party 의 Guard 튜플 삭제 아웃박스(`GuardDeleteOutbox`·`GuardDeleteOutboxRetrier`·`GuardTuples`·`GuardTupleDeleteOutbox` 와 테스트 목록), doro-blog 의 `blog-schema.doro`·`oauth-client-ids` 설정**이다. 그 외 서비스 동작(도메인 로직·UI)은 읽지 않았다 |
| 표기 | **[코드]** 소스에서 직접 확인 / **[미검증]** 추론이거나 실행해 보지 않음 |
| 미조사 | `doro-menu`, `doro-gallery`, `doro-interview`, `server-secret`(시크릿이라 열지 않음), 배포 서버의 실제 상태, 런타임 실행, 포털(`web`) UI 세부(§1.1·§8-D 의 토큰 보관 방식과 §8-B 6번의 세션 탭 목록 로직만 확인), (`bff` 모듈 저장소·마이그레이션과 `gateway/nginx.conf` 전문은 2차 검증에서 읽었다 — §4.7, §13). 전부 코드 읽기로만 검증했고 테스트·기동은 하지 않았다. 서버 상태·실행 결과가 필요한 항목만 `[미검증]` 으로 남기고, 각각 확인 명령을 옆에 적었다 |

**Doro 가 바뀌면 이 문서는 낡는다.** 코드를 수정하기 전에 §11 의 빠른 검증 명령으로 핵심 전제를 다시 확인할 것. 변경 내역은 §14.

---

## 1. 큰 그림

```
브라우저 ──▶ [Gateway nginx :443] ──▶ Portal(web) / IAM(auth) / 블로그·파티·게임 / 메뉴 …
                  │ (쿠키가 있는 서비스 API 요청마다 auth_request)
                  └────────────────▶ doro-bff :8085  (쿠키 세션 → 액세스 토큰, gateway 모드 서비스만)
서브서비스 ──(JWKS 조회, 로컬 서명검증)──▶ IAM  :8080  /.well-known/jwks.json
서브서비스 ──(선택: 세션 폐기 확인)──────▶ IAM  :8080  GET /api/v1/sessions/current
서브서비스 ──(내부 API, 서비스 토큰)─────▶ IAM  :8080  /internal/v1/**  (탈퇴 목록·계정 상태, §2.6)
서브서비스 ──(gRPC :9090 / REST :8081)──▶ Guard
IAM ──(REST, 관리자 판정·역할 튜플 동기화)──▶ Guard
```

| 구성요소 | 포트 | 역할 |
|---|---|---|
| IAM (`auth`) | 8080 | 가입/로그인/2FA/세션/토큰 발급, JWKS, OAuth 2.1/OIDC, 관리자 API |
| Guard (`guard`) | 8081 REST, 9090 gRPC | Zanzibar 방식 ReBAC. 튜플 저장 + `Check`/`Expand` |
| SDK (`sdk`) | (라이브러리) | JWT 로컬 검증 필터(검증 로직은 `DoroTokenVerifier`), `@DoroGuard`, `@CurrentDoroUser`, gRPC 클라이언트, **BFF 로그인 모듈**(`bff`)과 **탈퇴 동기화 모듈**(`sync`) — 서비스는 설정·저장소·사용자 훅만 구현한다. 자세한 규칙과 서비스가 할 일은 [SDK_BFF.md](SDK_BFF.md), 요약은 §4.7 |
| BFF (`bff`, 컨테이너 `doro-bff-api`) | 8085(호스트는 127.0.0.1 바인딩) | 브라우저 로그인(Doro OAuth)·세션·토큰 갱신을 **서비스 밖에서** 맡는다. 게이트웨이가 `auth_request` 로 쿠키를 액세스 토큰으로 바꿔 서비스에 `Authorization: Bearer` 로 붙인다. 구조·규칙은 [BFF.md](BFF.md), 요약은 §1.1·§4.7 |
| Portal (`web`) | 3000(→컨테이너 80) | 통합 계정 센터 UI (React). 로그인·계정·로그 뷰어·OAuth 동의 화면 |
| Gateway | 80/443 | nginx 리버스 프록시. 모든 서비스를 **같은 origin** 으로 묶음. 규칙은 `docs/GATEWAY_ROUTING_RULES.md` |

- 서비스별 **독립 DB** 규칙: IAM=`doro_auth`, Guard=`doro_guard`, BFF=`doro_bff`(compose 기준), 서브서비스=`service_{name}`. [코드: `docker-compose.yml`, `AGENTS.md`]
- **서비스별 DB 계정**: compose 는 서비스마다 접속 계정을 따로 받을 수 있다(`AUTH_DB_USER`/`AUTH_DB_PASSWORD`, `GUARD_DB_USER`/`GUARD_DB_PASSWORD`, `BFF_DB_USER`/`BFF_DB_PASSWORD`; 비우면 `POSTGRES_USER` 로 폴백). 서비스마다 자기 DB 의 소유자인 일반 계정(`<서비스>_app`)으로만 접속하게 바꾸는 도구가 `scripts/split-db-accounts.sh` 다(`--check`/`--apply <서비스>`/`--rollback <서비스>`, 서비스: blog party games auth guard bff). 새 서비스를 붙일 때 DB 이름은 그대로 `service_{name}` 이고, 계정 분리는 선택이다. [코드: `docker-compose.yml`, `scripts/split-db-accounts.sh` 본문]
  - `--apply <서비스>` 의 실제 동작: ① `pg_dump` 백업(비어 있으면 중단) ② `<서비스>_app` 계정 생성(`LOGIN` 만, 슈퍼유저·DB 생성·역할 생성·복제 권한 없음, 새 무작위 비밀번호; 계정이 있으면 비밀번호만 새로 정함) ③ DB·`public` 스키마·테이블·시퀀스·뷰·함수·타입의 소유자를 그 계정으로 변경하고 `PUBLIC` 의 `CONNECT` 를 거둠 ④ 새 계정으로 **비밀번호 인증을 거치는 로그인**(컨테이너 자기 IP, 127.0.0.1 은 trust 일 수 있어 쓰지 않음) 확인 — 실패하면 `.env` 를 바꾸지 않고 중단 ⑤ `.env` 백업(`.env.bak-dbsplit-<서비스>-<시각>`) 후 접속 계정·비밀번호를 쓰고 **그 서비스 컨테이너만** 재생성 → healthy 와 컨테이너의 실제 접속 계정 확인, 아니면 `.env` 를 되돌려 재생성. 이미 `<서비스>_app` 으로 접속 중이면 아무것도 하지 않는다(멱등). `auth`/`guard`/`bff` 는 compose 가 `<서비스>_DB_USER` 변수를 받지 않으면 시작 전에 중단한다. `--rollback <서비스>` 는 가장 최근 `.env` 백업으로 복원하고 재생성한다(계정·소유자는 그대로). `--check` 는 읽기 전용(소유자·접속 계정·현재 연결 수). 비밀번호는 화면에 출력하지 않는다. `scripts/test-split-db-accounts.sh` 가 CI 에서 이 로직을 시험한다.
  - 서버에 실제로 적용됐는지는 이 문서에 적지 않는다. 확인(읽기 전용): `ssh mini 'bash -s -- --check' < scripts/split-db-accounts.sh`(서비스별 DB 소유자·컨테이너 접속 계정 출력).
- DB 는 공용 PostgreSQL 컨테이너 하나에 여러 DB. Redis 는 **IAM 이 쓴다**(JWT 키, 킬스위치, OAuth 인가 코드). **Guard 와 BFF 는 Redis 를 쓰지 않는다**(compose 가 둘에 Redis 환경변수를 넘기지 않는다). [코드]
- 호스트 포트 바인딩 기본값: Postgres/Redis/Guard 는 `127.0.0.1`, auth(8080)·web(3000)은 `0.0.0.0`(`*_BIND` 로 변경). 운영에서는 외부에 22/80/443 만 연다. [코드: compose, `.env.example`]

### 1.1 "통합 로그인"은 세 가지 방식이 공존한다

1. **인앱 로그인 (포털 전용)**: 포털 프론트엔드가 같은 origin(게이트웨이)의 IAM API 를 직접 호출해 로그인한다. **포털은 리프레시 토큰을 HttpOnly 쿠키(`doro_rt_<슬롯>`)로만 받고 액세스 토큰은 탭 메모리에만 둔다**(localStorage 에는 토큰이 없고 `doro_auth_accounts` 에는 계정 목록·슬롯만 남는다). 다른 프론트엔드가 헤더 없이 호출하면 이전처럼 토큰이 응답 본문에 온다. 서브서비스 백엔드는 `Authorization: Bearer <JWT>` 를 받아 JWKS 로 검증한다. [코드: `web/src/store/authStore.ts`, `web/src/api/*`]
2. **서비스(블로그·도로 파티·도로 게임 천국)의 "도로로 로그인" = OAuth 인가 코드 + PKCE 를 서버가 수행하는 BFF**: 브라우저는 JavaScript 로 토큰을 보지 못하고 서비스별 세션 쿠키(`doro_<서비스>_session`)만 갖는다. BFF 는 두 모드로 돈다. 서비스 설정 `blog.auth.mode` / `party.auth.mode` / `games.auth.mode`(환경변수 `BLOG_AUTH_MODE` 등, **코드 기본값은 `embedded`**): [코드: 각 서비스 `application.yaml`, `docs/BFF.md`]
   - `embedded`: 서비스 안의 SDK BFF 모듈(§4.7)이 로그인·세션을 처리한다.
   - `gateway`: **서비스 밖의 `doro-bff`** 가 처리한다. 게이트웨이가 서비스 API 요청마다(쿠키가 있을 때만) BFF 에 `auth_request` 를 보내 쿠키를 액세스 토큰으로 바꾸고, 서비스에는 `Authorization: Bearer <토큰>` 을 붙여 전달한다(BFF 가 토큰을 돌려준 요청에서만 클라이언트가 보낸 `Authorization` 을 **덮어쓴다**. 쿠키가 없거나 세션이 만료·폐기돼 BFF 가 토큰 없이 204 를 주면 클라이언트가 보낸 `Authorization`/`X-API-Key` 를 그대로 통과시킨다 — `gateway/nginx.conf` 의 `map $bff_token $bff_authorization`). 서비스에는 세션·리프레시 토큰이 없고 Bearer 검증과 `GET /api/v1/bff/session` 만 있다. 쿠키가 있는데 BFF 에 닿지 못하면 익명으로 넘기지 않고 JSON 503, CSRF 증명이 없으면 JSON 403(`AUTH-403-03`)으로 서비스에 닿기 전에 끝난다. 쿠키 없는 요청(공개·API 키)은 BFF 를 거치지 않는다. [코드: `docs/BFF.md`, `bff/.../AuthRequestController`]
   - **모드 스위치 구현** [코드: 각 서비스 `config/ConditionalOnEmbeddedBff`, `*BffConfig`, `BlogBffStores`/`PartyBffStores`, `BffSessionController`]: 세 서비스 모두 `@ConditionalOnProperty(prefix="<서비스>.auth", name="mode", havingValue="embedded", matchIfMissing=true)` 인 `@ConditionalOnEmbeddedBff` 로 SDK `BffProperties` 빈·세션 저장소 빈·로그인 성공 훅을 만든다. 값이 `gateway`(또는 `embedded` 가 아닌 아무 값)면 이 빈들이 없어 SDK `DoroBffAutoConfiguration`(`BffProperties`+`BffSessionStore`+`BffLoginAttemptStore` 셋이 모두 있어야 켜짐)도 꺼진다. `GET /api/v1/bff/session` 컨트롤러와 탈퇴 동기화 빈은 **모드와 무관하게 항상** 있다(gateway 모드에서는 이 조회가 사용자 행을 처음 만든다).
   - 서비스별 **운영 모드**는 서버 설정이라 이 문서에 적지 않는다. 확인(읽기 전용): `ssh mini 'bash -s -- party --check' < scripts/set-bff-mode.sh`(blog|party|games 각각).
   - BFF 가 쓰는 OAuth 클라이언트는 `firstParty: true` 로 등록하고, 서비스 SDK 의 `doro.iam.oauth-client-ids` 에 그 클라이언트 ID 를 넣어야 BFF 가 받은 `cid` 토큰이 통과한다(§4.1). doro-bff 의 기본 클라이언트: `doro-blog`, `doro-party`, `doro-games`. [코드: `bff/src/main/resources/application.yaml`]
3. **OAuth 2.1 인가 코드 + PKCE / OIDC (외부·별도 origin 앱용)**: 리다이렉트 방식 SSO. 클라이언트 등록(`oauth_clients`), 포털 동의 화면, 토큰/리프레시/폐기/`id_token`/`userinfo` 가 구현돼 있다(§5). 공개 클라이언트(PKCE)와 **기밀 클라이언트(PKCE + client_secret)** 를 지원한다. (위 2번도 같은 엔드포인트를 쓰는 OAuth 클라이언트다.)

---

## 2. IAM API — 실제 규격

기본 URL `http://localhost:8080`(게이트웨이 경유 시 같은 경로, 단 `/api/v1/admin/oauth/**` 는 게이트웨이가 IAM 으로 보내지 않는다 — §9). **JSON 필드는 camelCase.**

### 2.1 응답 형식 — 두 가지가 섞여 있다

```jsonc
// 성공: ApiResponse 봉투
{ "success": true, "data": { ... }, "timestamp": "2026-10-02T03:00:00Z" }

// 실패: ErrorResponse (봉투 아님! success 필드 없음)
{ "timestamp": "...", "status": 401, "error": "Unauthorized",
  "code": "INVALID_CREDENTIALS", "message": "...", "path": "/api/v1/auth/login",
  "details": [ { "field": "email", "rejectedValue": "x", "reason": "..." } ] }
```

- 성공은 항상 `data` 를 꺼내 쓴다. 실패의 `code` 는 **enum 이름**(`INVALID_CREDENTIALS`)이다. `AUTH_40102` 같은 숫자 코드는 enum 안에만 있고 응답에는 나오지 않는다. 입력 검증 실패는 `code: "INVALID_INPUT_VALUE"`(+`details`, 비밀번호/토큰/코드 필드의 `rejectedValue` 는 숨김). [코드: `GlobalExceptionHandler`, `ErrorCode`]
- 워크스페이스 룰(`{success:false, code, message, status}`)과는 **다르다**. 서브서비스는 자체 `ApiResponse.error` 를 쓴다(doro-blog 참고). SDK 기본 예외 어드바이스는 룰과 같은 모양 `{success:false, code, message, status}` 로 응답한다(§4.6).
- 요청 제한 429 는 필터가 쓰는 간단한 본문(`{status, error, code:"TOO_MANY_REQUESTS", message}`)이고 `Retry-After` 헤더가 붙는다.
- **503 두 가지**(둘 다 `ErrorResponse`): `SERVER_BUSY` — 비밀번호 해싱(Argon2id, 64MB) 대기 줄이 가득 차 새 요청을 받지 못함(동시 해싱 기본 8·대기 32·대기 시간 3초 초과, `Retry-After: 2`; **비밀번호가 틀린 것이 아니다**, 재시도). `GUARD_UNAVAILABLE` — 역할 변경처럼 Guard 튜플 동기화가 실패해야 하는 흐름에서 Guard 에 닿지 못함(DB 역할은 바뀌지 않는다, §6.6). [코드: `CustomArgon2PasswordEncoder`, `UserRelationSyncService`, `GlobalExceptionHandler`]
- 새 409/404 코드: `ACCOUNT_STATE_CONFLICT`(정지·해제를 할 수 없는 계정 상태, `AUTH_40903`), `BOOTSTRAP_ALREADY_DONE`(최고 관리자가 이미 있음), `BOOTSTRAP_UNAVAILABLE`(부트스트랩이 꺼져 있을 때 일반 404 문구). [코드: `ErrorCode`]
- OAuth 토큰 엔드포인트의 **form 요청** 응답/오류는 RFC 6749 형식이다(§5).

### 2.2 엔드포인트

`/api/v1/auth/**` 는 **인증 없이 접근할 수 있는 경로**(permitAll)다. 단 `2fa/*`, `logout` 은 컨트롤러가 Bearer(인증된 사용자)를 요구해 없으면 401 `UNAUTHORIZED` 를 낸다. 그 외 `/api/v1/**` 는 인증 필요, `/api/v1/admin/**` 는 JWT 역할 ADMIN 이상 + 서비스 안에서 Guard 재판정. [코드: `SecurityConfig`]

| 메서드·경로 | 요청 | 응답 `data` | 비고 |
|---|---|---|---|
| `POST /api/v1/auth/signup` | `{email, password(8~64자), name(2~50자)}` | `{userId}` (201) | 이메일은 trim+소문자로 저장, 중복 검사는 대소문자 무시(409 `EMAIL_ALREADY_EXISTS`) |
| `POST /api/v1/auth/login` | `{email, password(≤128자), deviceInfo?(≤255자)}` | `{requires2fa, tempTicket?, tokens?}` | `clientIp/userAgent` 는 서버가 추출. 2FA 계정은 `requires2fa:true`+`tempTicket`, 아니면 `tokens`. **계정 상태는 올바른 비밀번호를 낸 뒤에야 알려 준다**: 정지면 403 `ACCOUNT_SUSPENDED`, 영구 탈퇴(`DELETED`) 계정은 자격증명이 지워져 로그인할 수 없고, **탈퇴 유예(`PENDING_DELETION`) 계정은 로그인 성공 시 탈퇴가 취소(복구)된다**(조건부 UPDATE, Guard 튜플은 되살리되 실패해도 로그인은 막지 않음). 비밀번호 검증은 DB 트랜잭션 밖에서 하고 세션·토큰 발급만 트랜잭션이다 |
| `POST /api/v1/auth/2fa/login` | `{tempTicket, code(6자리 숫자), deviceInfo?}` | `TokenResponse` | |
| `POST /api/v1/auth/2fa/setup` | (Bearer) `{currentPassword}` | `{secret, qrUri}` | 현재 비밀번호로 재확인(틀리면 401, 로그인과 같은 잠금 횟수에 합산). 시크릿을 **대기(pending)** 로만 저장. 이미 활성이면 400 |
| `POST /api/v1/auth/2fa/verify` | (Bearer) `{code}` | 없음 | 대기 중이면 **코드 확인 후 활성화**, 이미 활성이면 코드 검증만. 실패는 잠금 카운트에 합산 |
| `POST /api/v1/auth/2fa/disable` | (Bearer) `{code}` | 없음 | **현재 OTP 코드로 재인증**해야 해제. 실패는 잠금 카운트에 합산 |
| `POST /api/v1/auth/token/refresh` | `{refreshToken}` 또는 쿠키 | `TokenResponse` | RTR. 이전 토큰 즉시 폐기. 쿠키 방식(아래): 헤더 `X-Doro-Cookie-Session: 1` + `X-Doro-Account-Slot: 0~4` 를 보내면 쿠키 `doro_rt_<슬롯>`(HttpOnly·Secure·SameSite=Strict·Path=/api/v1/auth)의 토큰으로 갱신하고 새 쿠키를 내려주며 본문에는 리프레시 토큰이 없다. **쿠키로 온 토큰은 이 헤더가 없으면 400**(CSRF 방어). 본문 토큰이 있으면 그것을 우선해 한 번에 쿠키로 옮길 수 있다. 서버가 4xx 로 거부하면 그 슬롯의 쿠키를 지운다. 로그인·2FA 로그인·로그아웃도 같은 헤더로 쿠키를 설정·삭제. **OAuth 클라이언트 세션의 리프레시 토큰은 이 엔드포인트에서 400 `INVALID_TOKEN`**(회전하지 않으므로 클라이언트 토큰을 소모하지 않는다. OAuth 토큰은 `/oauth2/token` 의 `refresh_token` grant 로만 갱신) |
| `POST /api/v1/auth/logout[?sessionId=<uuid>]` | (Bearer) | 없음 | **Bearer 필수**. `sessionId` 생략 시 토큰의 `sid`(현재 세션). **본인 세션만** 종료(남의 세션/없는 세션은 404 `SESSION_NOT_FOUND`) |
| `POST /api/v1/auth/lookup` | `{email}` | `{email,name,profileImageUrl}` | 없으면 404 `USER_NOT_FOUND` → 계정 존재 여부 노출(요청 제한으로만 완화). 정지·탈퇴 유예 여부는 드러내지 않는다. 인증 없이 부를 수 있어 **프로필 이미지 data URL 이 `doro.iam.lookup.max-image-length`(`DORO_IAM_LOOKUP_MAX_IMAGE_LENGTH`, 기본 50000자)를 넘으면 `profileImageUrl` 은 null** |
| `GET /api/v1/users/me` | (Bearer) | `{id,email,name,profileImageUrl,status,role,hasTotp,createdAt}` | |
| `PATCH /api/v1/users/me` | (Bearer) `{name?, profileImageUrl?}` | 프로필 | 이미지 길이 상한 `doro.iam.profile.image-max-length`(기본 1048576자). 값은 `https://` 주소나 이미지 `data:` URL(png·jpeg·gif·webp·svg+xml)만 허용, 빈 문자열은 이미지 제거 |
| `PUT /api/v1/users/me/password` | (Bearer) `{currentPassword, newPassword(8~64자)}` | 없음 | 성공하면 **현재 세션을 제외한 모든 세션 종료** |
| `POST /api/v1/users/me/deletion` | (Bearer) `{password, totpCode?}` | `{scheduledPurgeAt}` | 회원탈퇴 요청. 비밀번호(2FA 계정은 OTP 코드)로 재확인(실패는 로그인과 같은 잠금 횟수에 합산) → `PENDING_DELETION` + 모든 세션 종료. **관리자(ADMIN/SUPER_ADMIN)는 역할을 USER 로 내린 뒤에야 탈퇴할 수 있다(400)**. 유예(`DORO_IAM_ACCOUNT_DELETION_GRACE_DAYS`, 기본 30일) 안에 로그인하면 복구, 지나면 주기 작업(`AccountPurgeService`)이 ① Guard 의 IAM 튜플 삭제(실패하면 상태를 안 바꾸고 다음 주기에 재시도) ② 조건부 UPDATE 로 개인정보 익명화(`DELETED`, 이메일 `deleted-<id>@deleted.invalid`, 이름 "탈퇴한 사용자") ③ 자격증명·세션 삭제 순으로 처리. 서비스는 내부 API(§2.6)로 동기화. [코드: `AccountDeletionService`, `AccountPurgeService`] |
| `GET /api/v1/sessions` | (Bearer) | 활성 세션 목록 | |
| `GET /api/v1/sessions/current` | (Bearer) | 204 | 현재 토큰의 세션이 DB 기준 유효한지. 무효면 401 `SESSION_EXPIRED`, 계정 정지면 403. SDK 의 폐기 확인이 사용(§4.1) |
| `DELETE /api/v1/sessions/{id}` | (Bearer) | 없음 | **소유자 검사**(타인 세션은 404) + 리프레시 토큰 폐기 + 킬스위치 |
| `POST /api/v1/sessions/revoke-others?currentSessionId=` | (Bearer) | 없음 | 현재 세션만 남기고 종료(소유자 검사 포함) |
| `GET /api/v1/admin/users` | (Bearer, ADMIN+) | 사용자 목록 | JWT 역할 + Guard `system:doro#admin` 으로 최종 판정. 쿼리 `page`(0부터)·`size`(1~500, 기본 50)·`q`(이름·이메일 부분 일치, ≤100자)·`filter`(`ALL`/`SUPER_ADMIN`/`ADMIN`/`USER`/`TOTP`/`SUSPENDED`)를 하나라도 주면 서버 페이지네이션(`createdAt,id` 순), **전체 수는 `X-Total-Count` 헤더**. 파라미터가 없으면 전체를 돌려준다. 관리자 목록에만 `suspendedAt`/`suspensionReason`/`locked` 가 채워진다 |
| `GET /api/v1/admin/users/summary` | (Bearer, ADMIN+) | `{total, superAdmins, admins, users, totp, suspendedOrLocked}` | 목록 탭별 개수(검색과 무관한 전체 기준) |
| `PATCH /api/v1/admin/users/{id}/role` | (Bearer) `{role}` | 프로필 | Guard `system:doro#manage_roles`. 본인 역할 변경 불가. 변경 후 대상의 **모든 세션 종료** |
| `DELETE /api/v1/admin/users/{id}/2fa` | (Bearer) | 없음 | Guard `user:{id}#can_reset_2fa`. 성공하면 대상의 모든 세션 종료 |
| `PUT /api/v1/admin/users/{id}/suspension` | (Bearer, ADMIN+) `{reason}` | 프로필(`status:SUSPENDED`, `suspensionReason`, `suspendedAt`) | 사유 필수·200자. Guard `system:doro#admin`; 관리자(ADMIN) 대상은 `manage_roles`(최고 관리자)만, **본인·최고 관리자는 정지 불가**. 로그인·갱신·OAuth 발급 차단 + 대상의 **모든 세션 종료**(액세스 토큰도 즉시 무효). 이미 정지면 변화 없음, 탈퇴 유예·탈퇴 계정은 409 `AUTH_40903` |
| `DELETE /api/v1/admin/users/{id}/suspension` | (Bearer, ADMIN+) | 프로필 | 정지 해제(같은 인가 규칙). 정지 중이 아닌 탈퇴 유예 계정은 409 |
| `DELETE /api/v1/admin/users/{id}/lock` | (Bearer, ADMIN+) | 프로필(`locked:false`) | 비밀번호·OTP 연속 실패로 잠긴 계정의 잠금 해제(같은 인가 규칙). 세션은 건드리지 않음 |
| `POST /api/v1/auth/bootstrap` | (Bearer) | 첫 관리자 부트스트랩 | `DORO_IAM_BOOTSTRAP_TOKEN`(32자↑, 기본 꺼짐)을 아는 로그인 사용자가 본인을 SUPER_ADMIN 으로 승격. 최고 관리자가 있으면 409, 토큰 미설정 404, 틀리면 403, 성공 시 모든 세션 종료. [코드: `BootstrapService`] |
| `GET/POST/DELETE /api/v1/admin/oauth/clients` | (Bearer, ADMIN+) | OAuth 클라이언트 | 등록/목록/비활성화. §5.3 |
| `GET /api/v1/admin/authz` | (Bearer, ADMIN+) | 204 | 게이트웨이 `auth_request` 전용(로그 뷰어 보호). Guard `system:doro#admin` 최종 판정 |
| `GET /.well-known/jwks.json` | 없음 | JWKS(응답 봉투 없음) | 서브서비스가 사용 |
| `GET /.well-known/openid-configuration` | 없음 | OIDC 메타데이터 | §5 |
| `GET /health`, `/actuator/health` | 없음 | 상태 | `/actuator/**` 나머지는 ADMIN 필요 |

`TokenResponse` = `{accessToken, refreshToken, tokenType:"Bearer", expiresIn, sessionId, userIndex}` (+ OAuth 교환 응답에서만 `idToken`, `scope`). [코드]

**존재하지 않는 것**: `/api/v1/auth/accounts/add`, `/accounts/switch/{uidx}`, `/2fa/enable`, `/login/2fa`. 다중 계정 "Google 스타일 전환"은 **서버가 아니라 포털 프론트엔드**(여러 계정 토큰을 localStorage 에 보관)가 한다. 서버의 `uidx` 는 사용자별 활성 세션 순번(max+1)일 뿐이다. 사용자 상태 중 `LOCKED` 를 직접 바꾸는 API 는 없다(잠금은 로그인 실패로 걸리고, 관리자 `DELETE /api/v1/admin/users/{id}/lock` 으로 푼다). 정지는 `PUT/DELETE /api/v1/admin/users/{id}/suspension`, 첫 관리자는 `POST /api/v1/auth/bootstrap` 으로 한다. [코드]

### 2.3 로그인 보안 동작 [코드]

- 잠금은 **(계정, IP) 쌍 5회/15분 → 그 IP 만 15분, 계정 전체 30회/1시간(여러 IP 합산, 쌍 잠금 중 시도는 제외) → 1시간 → HTTP 403** `ACCOUNT_LOCKED`. IP 는 IPv4 는 그대로, **IPv6 는 앞 64비트(/64)로 묶어** 센다(`IpPrefix`). 비밀번호 재확인이 필요한 API(2FA 설정, 비밀번호 변경, 회원탈퇴)의 비밀번호 실패도 같은 카운트에 합산된다. 이메일만 알아서는 남의 계정을 잠글 수 없게 한 정책이다. 2FA(OTP) 실패와 `2fa/verify`·`disable` 실패도 같은 카운트에 합산된다. 카운터는 메모리의 `LoginAttemptTracker`(재시작하면 초기화, 계정 전체 잠금만 DB `credentials.locked_until`)이고 수치는 `doro.iam.lockout.*`. 풀기: 관리자 API `DELETE /admin/users/{id}/lock`(둘 다 해제), 비상시 서버에서 `scripts/unlock-account.sh`. [코드: `LoginAttemptTracker`, `CredentialService`]
- 2FA 티켓: 5분 유효, OTP 5회 실패 시 폐기. **인메모리 저장**이라 IAM 재시작/다중 인스턴스에서 깨진다(보관 상한 `max-pending-tickets` 초과 시 429).
- OTP: 시간 오차 ±1 스텝 허용, **같은 스텝 재사용 방지**(인메모리 맵).
- 비밀번호는 Argon2id (`CustomArgon2PasswordEncoder`).
- **세션**: 사용자당 동시 활성 세션 **상한(기본 20, `DORO_IAM_SESSION_MAX_ACTIVE_PER_USER`)** — 초과하면 가장 오래된 세션부터 종료. (`application.yaml`·compose 기본이 20 이다. `SessionService` 의 `@Value` 폴백 문자열은 10 이지만 yaml 이 항상 덮어쓴다.) **같은 IP+User-Agent** 의 이전 활성 세션은 새 로그인 시 자동 종료. OAuth 클라이언트 세션은 IP·UA 가 클라이언트마다 고정값이라 "같은 기기" 규칙 대신 **서비스(클라이언트)별 기기 수 상한**(`DORO_IAM_SESSION_MAX_OAUTH_PER_CLIENT`, 기본 3, §5.2)으로 관리한다. 종료는 모두 `SessionRevocationService` 를 거쳐 **세션 비활성화 + 리프레시 토큰 폐기 + 킬스위치 발행**이 한 흐름에서 일어난다. 동시 로그인에서는 상한을 잠깐 넘을 수 있다(직렬화 안 함).
- 세션 만료는 **슬라이딩**: 리프레시가 성공할 때마다 `expiresAt = now + 30일`. 만료 세션은 주기적으로 비활성화되고, 비활성 세션과 만료·폐기된 리프레시 토큰은 보존 기간(`DORO_IAM_TOKEN_PURGE_RETENTION_DAYS`, 기본 90일, `DORO_IAM_TOKEN_PURGE_ENABLED` 로 끔) 뒤에 배치로 삭제된다. 세션이 이미 끝난 뒤(로그아웃·비밀번호 변경) 폐기된 토큰을 다시 내면 `401 SESSION_EXPIRED` 이고, 회전으로 폐기된 토큰이 활성 세션에서 다시 오면 `TOKEN_REUSE_DETECTED` 로 세션 전체를 끊는다. 여기에 **절대 수명**(`doro.iam.session.absolute-lifetime-seconds`, `DORO_IAM_SESSION_ABSOLUTE_LIFETIME_SECONDS`, 기본 **90일**, 0 이하면 끔)이 더해진다: 세션을 만든 시각(로그인)부터 이 시간이 지나면 활동이 있어도 리프레시가 `401 SESSION_EXPIRED` 로 거부되고, 세션 종료·리프레시 토큰 폐기·킬스위치 발행이 일어나 다시 로그인해야 한다. 새 리프레시 토큰의 만료도 절대 수명 끝을 넘지 않는다. 일반 로그인과 OAuth 클라이언트 세션 모두 `RefreshTokenService.rotateRefreshToken` 을 거치므로 똑같이 적용된다.
- 리프레시 토큰 재사용(이미 회전된 토큰 재제시)은 공격으로 판정해 해당 패밀리와 세션을 종료하고 400 `TOKEN_REUSE_DETECTED`. 회전은 조건부 UPDATE 로 원자적이라 동시 요청 중 하나만 성공한다. 다중 탭 경합을 허용하는 유예(`doro.iam.jwt.refresh-reuse-grace-seconds`)는 **기본 0(꺼짐)**.
- 비밀번호 변경·역할 변경·관리자 2FA 초기화 시 관련 세션이 종료된다(위 표).

### 2.4 요청 제한과 클라이언트 IP [코드]

- IP 단위 고정 윈도우, 초과 시 **429 + `Retry-After`**: 로그인/2FA 로그인/**부트스트랩(`POST /auth/bootstrap`)/비밀번호 변경(`PUT /users/me/password`)** 10분당 20회, 계정 조회 10분당 30회, 가입 1시간당 10회, `POST /oauth2/token`·**`POST /oauth2/revoke`** 및 **Bearer 없는** `GET /oauth2/authorize` 10분당 60회. 키는 IPv6 를 /64 로 묶는다. 경로는 퍼센트 디코딩·정규화한 값(`RequestPaths.canonical`)으로 판정해 `/log%69n` 같은 인코딩으로 빗나가지 않는다. 429 가 폭주해도 경고 로그는 버킷별로 일정 간격에 한 번만 남긴다. `doro.iam.rate-limit.*` 로 조정, `DORO_IAM_RATE_LIMIT_ENABLED=false` 로 끈다. **단일 인스턴스 메모리 기준**.
- 클라이언트 IP(`ClientIpResolver`): **신뢰 프록시(`doro.iam.trusted-proxies`, 기본 루프백 + 도커 브리지 `172.16.0.0/12`)** 에서 온 연결의 `X-Real-IP` 만 신뢰한다. LAN(192.168/16, 10/8)이나 인터넷에서 온 헤더는 무시. 요청 제한·세션 기록·로그가 같은 규칙을 쓴다. 게이트웨이는 `X-Real-IP`/`X-Forwarded-For` 를 **접속 주소로 덮어쓴다**.

### 2.5 CORS [코드]

`doro.cors.allowed-origin-patterns`(`DORO_CORS_ALLOWED_ORIGIN_PATTERNS`, 쉼표 구분 **명시적 패턴 목록**) 만 허용하고 `allowCredentials(true)`. **베어 `*` 는 기동 시 거부**한다. 기본값은 공개 도메인, 로컬 LAN 주소, `localhost:*`, `127.0.0.1:*`. 허용 헤더는 `Authorization, Content-Type, Accept, X-Trace-Id, X-Requested-With`, 브라우저에 노출하는 응답 헤더는 `X-Trace-Id, X-Total-Count`(관리자 목록 페이지네이션용). Guard 는 브라우저용 CORS 를 제공하지 않는다. 새 Origin 에서 브라우저가 IAM 을 직접 호출해야 하면 이 목록에 추가한다(`permitAll`/`*` 로 우회 금지).

### 2.6 내부 API (`/internal/v1/**`, 서브 서비스 전용) [코드: `InternalUserController`, `InternalApiAuthFilter`, `SecurityConfig`]

Docker 내부 네트워크에서 서비스가 직접 부른다. 게이트웨이에는 `/internal/` location 이 **없다**: 공개 주소의 `/internal/...` 은 게이트웨이의 마지막 `location /` 에 걸려 `301 /blog/internal/...` 로 가고 IAM 에 닿지 않는다(404 가 아니라 301). 게이트웨이의 `/iam/` 는 접두어를 떼고 IAM 으로 전달하지만 `X-Real-IP`/`X-Forwarded-For` 를 덮어쓰므로 `/iam/internal/v1/...` 은 IAM 에 닿아도 **404**(토큰이 맞아도, 토큰이 없어도)다. 즉 **`X-Forwarded-For`/`X-Real-IP` 가 붙은 요청은 항상 404**(필터와 컨트롤러 둘 다 검사) — 위 게이트웨이 동작은 `gateway/nginx.conf` 를 읽어 확인했고 `scripts/gateway-routing/expect-current.txt` 에는 `/internal` 항목이 없다(시험 대상 아님). 호출자는 헤더 `X-Doro-Service-Token` 으로 `DORO_IAM_INTERNAL_TOKENS`(`이름:토큰,…`)에 등록된 토큰을 보내야 하고(모드 `DORO_IAM_INTERNAL_AUTH_MODE`: ENFORCE 기본 / WARN / OFF), 없거나 틀리면 401(표준 오류 봉투). 응답은 `ApiResponse` 봉투, **개인정보 없음**.

| 메서드·경로 | 쿼리 | 응답 `data` | 용도 |
|---|---|---|---|
| `GET /internal/v1/deleted-users` | `since`(ISO 시각, 기본 epoch), `limit`(기본 200, 최대 500) | `{items:[{userId, deletedAt}], nextSince}` | 영구 탈퇴한 사용자 ID 를 증분으로 가져와 서비스가 자기 데이터를 정리(SDK 탈퇴 동기화가 사용, §4.7) |
| `GET /internal/v1/users/{userId}/status` | 없음 | `{userId, status}` — `ACTIVE`/`SUSPENDED`/`PENDING_DELETION`/`DELETED`(+`LOCKED` 값이 enum 에 있음) | IAM 세션 없이 오래 쓰는 자격 증명(예: 블로그 API 키)이 정지·탈퇴 유예 동안 쓰이지 않게 확인. 없는 사용자는 404 |

- `deleted-users` 의 `nextSince` 는 마지막 항목의 `deletedAt`(없으면 요청한 `since`)이다. 사용자마다 처리 시각을 따로 기록하므로(익명화 시 `Instant.now()`) 같은 시각 묶음에서 끊기지 않고, 겹쳐 받아도 되도록 설계돼 있다. 핸들러는 같은 ID 를 다시 받아도 결과가 같게(멱등) 작성한다. [코드: `AccountPurgeService`, SDK `DeletedUsersProperties.overlap`]
- 이 경로는 Spring Security 상 `permitAll`(GET, 두 경로만)이라 JWT 가 필요 없고, **`InternalApiAuthFilter`**(`HIGHEST_PRECEDENCE+10`, `/internal` 또는 `/internal/` 접두 경로만; 경로는 퍼센트 디코딩·`//`·`/./`·`/../` 정규화 후 판정)가 막는다. 순서: ① 프록시 헤더가 있으면 404 ② 모드 OFF 면 통과 ③ 토큰이 등록된 값과 같으면 통과 ④ 아니면 WARN 모드는 경고만 남기고 통과, ENFORCE 는 **401**(`ApiResponse` 실패 봉투, 코드 `AUTH_40101`). 사용자 JWT(`Authorization: Bearer`)는 호출자 인증을 대신하지 못한다. ENFORCE 인데 `DORO_IAM_INTERNAL_TOKENS` 가 비어 있으면 모든 `/internal` 요청이 401 이다(기동 때 경고 로그). 알 수 없는 모드 값은 기동 실패. 토큰 값은 로그에 남지 않는다. [코드·테스트: `InternalApiAuthFilter`, `InternalApiAuthFilterTest`(ENFORCE/WARN/OFF·프록시·경로 우회), `AccountDeletionTest`(피드 인증: 빈 토큰·한 글자 짧거나 긴 토큰·Bearer 거부)]
- **`deleted-users` 세부** [코드·테스트: `InternalUserController`, `UserRepository.findDeletedSince`, `AccountDeletionTest`]: `since` 는 **포함**(`deletedAt >= since`)이고 `deletedAt ASC, id ASC` 순서다. `limit` 은 1~500 으로 잘린다(0·음수는 1, 초과는 500). `since` 형식이 틀리면 400, 미래 시각이면 빈 목록(`nextSince`=요청한 `since`). 항목은 `{userId, deletedAt}` 뿐이다(`DeletedUserRef`) — 이메일·이름 없음.
- **`users/{userId}/status` 세부** [코드·테스트: `UserStatusResponse`, `InternalUserStatusTest`]: 응답 `data` = `{userId, status}`, `status` 는 `UserStatus` enum 이름(`ACTIVE`/`LOCKED`/`SUSPENDED`/`PENDING_DELETION`/`DELETED`)을 DB 값 그대로 돌려준다(정지가 풀리면 다시 `ACTIVE`). 없는 사용자는 본문 없는 404. 테스트가 확인하는 것: 상태 4종 반영, 없는 사용자 404, 토큰 없음/틀림 401, `X-Forwarded-For`·`X-Real-IP` 404, 응답에 이메일·이름 없음. 라이브 서버의 실제 응답은 호출하지 않았다 — 확인(서버에서, 토큰 없이): `ssh mini "curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/internal/v1/deleted-users"` 가 `401` 이면 ENFORCE, `200` 이면 WARN/OFF 다. 정상 호출 응답은 서비스 토큰이 필요하므로 서버의 `.env` 값을 채팅·로그에 옮기지 않고 서버 안에서만 시험한다(`[미검증]`: 운영 모드·토큰 등록 상태).

---

## 3. 토큰

| 항목 | 값 | 출처 |
|---|---|---|
| 알고리즘 | RS256, 헤더 `kid`(기본 `doro-iam-key-2026-v1`) | `JwtTokenProvider` |
| **액세스 토큰 TTL** | **900초(15분)** 기본. `DORO_IAM_ACCESS_TOKEN_TTL_SECONDS` 로 조정 | `application.yaml`, compose |
| 리프레시 토큰 TTL | 2592000초(30일), 불투명 랜덤값(SHA-256 해시로 DB 저장). 회전 때마다 30일 연장 | `RefreshTokenService` |
| 세션 무활동 만료 / 절대 수명 | 무활동 30일 슬라이딩, 로그인 후 최대 90일. 사용자당 최대 활성 세션 20(OAuth 클라이언트당 3기기) | `application.yaml` |
| 일반 로그인 토큰 클레임 | `sub`(userId UUID), `email`, `sid`(세션 UUID), `uidx`(int), `role`(`USER`/`ADMIN`/`SUPER_ADMIN`), `iss`, `iat`, `exp` | `JwtTokenProvider` |
| OAuth 액세스 토큰 | 위와 같되 **`role` 은 항상 `USER`** + **`cid`(client_id)** 클레임 | `createOAuthAccessToken` |
| OIDC `id_token` | `aud=client_id`, `sid`, `auth_time`, (`nonce`), 스코프별 `email`/`name`/`picture`. **액세스 토큰이 아니다** | `createIdToken` |
| `iss` 값 | `doro.iam.issuer` = `DORO_IAM_ISSUER`, 기본 `https://auth.doro.local`(실제 도메인 아님) | `application.yaml` |

### 3.1 IAM 이 토큰을 받아들이는 범위 [코드: `JwtAuthenticationFilter`]
- 서명/만료 검증 후 **Redis 킬스위치(세션 블랙리스트)** 를 확인한다. 폐기된 세션이면 인증 없이 통과(→ 보호 경로에서 401).
- **`aud` 가 있는 토큰(id_token)** 은 IAM 의 어떤 경로에서도 인증으로 인정하지 않는다.
- **`cid` 가 있는 OAuth 액세스 토큰**은 `/oauth2/userinfo` 와 `/api/v1/sessions/current` **에서만** 인정한다(관리자 API 등으로의 권한 상승 차단).
- 서브서비스(SDK)는 이 구분이 없다: OAuth 액세스 토큰도 일반 액세스 토큰처럼 통과한다(`role` 은 `USER`). 그래서 서비스는 `role` 클레임이 아니라 Guard 로 권한을 판정해야 한다.

### 3.2 서명키 관리 [코드: `JwtKeyProvider`]
- 키 출처 우선순위: 설정 PEM(`private-key-pem`/`public-key-pem`) → Redis(`doro:iam:jwt:keypair:*`) → 신규 RSA-2048 생성 후 Redis 저장. **Redis 를 읽거나 새 키를 저장하지 못하면 기동을 중단한다**(`doro.iam.jwt.fail-on-key-store-error`, 기본 true; 읽기 실패를 "키 없음"으로 보고 새 키를 만들면 발급된 모든 토큰이 무효가 되기 때문. 끄는 것은 Redis 없는 테스트용). 새 키 저장은 개인키·공개키를 한 번에(`MSETNX`) 쓰고, 다른 인스턴스가 먼저 저장했으면 방금 만든 키를 버리고 저장된 키를 쓴다.
- **Redis 에 개인키를 AES-256-GCM 으로 암호화(`enc:v1:` 접두사)해 저장하는 것은 선택**이다. `DORO_IAM_JWT_KEY_ENCRYPTION_SECRET` 이 없으면 **평문**으로 저장하고 기동 시 ERROR 로그를 남긴다. 평문 키가 이미 있고 시크릿을 새로 설정하면 다음 기동 때 자동으로 암호화 형식으로 재저장한다. 암호화된 키가 있는데 시크릿이 없거나 틀리면 **새 키로 덮어쓰지 않고 기동을 중단**한다(시크릿 분실 주의).
- **키 로테이션**: `previous-key-id` + `previous-public-key-pem`(이전 공개키)을 지정하면 JWKS 에 `[새 키, 이전 키]` 를 함께 게시하고 이전 kid 토큰도 만료 전까지 검증한다. 이전 kid 는 현재 kid 와 달라야 한다(같으면 기동 실패). 절차는 `auth/src/main/resources/application.yaml` 의 주석. Redis 에 있는 키를 바꾸는 도구는 없다.
- JWKS `n`/`e` 는 선행 0x00 을 제거한 **최소 길이 unsigned big-endian**(RFC 7518)이라 일반 JWT 라이브러리와 호환된다. (같은 `kid` 에 키가 바뀌는 상황은 §4.5 의 TTL 갱신이 처리한다.)

### 3.3 로그아웃과 토큰 수명
- 로그아웃/세션 종료는 DB 세션 비활성화, 리프레시 토큰 폐기, Redis 킬스위치(블랙리스트 키 7일 + Pub/Sub)를 함께 처리한다. **IAM 자신의 API 는 즉시 차단**한다.
- **서브서비스는 기본적으로 폐기를 모른다.** SDK 의 서명 검증은 로컬이라 폐기된 액세스 토큰도 **만료(기본 15분)까지** 통과한다. 즉시 반영하려면 서비스에서 SDK **세션 폐기 확인**(`doro.iam.revocation-check`)을 켠다(§4.1). 기본은 `OFF`.
- 킬스위치는 Redis 에 의존하며 Redis 장애 시 **fail-open** 이다(IAM 필터가 조회 예외를 무시한다).

---

## 4. Doro SDK — 실제 동작

**의존성**: `implementation 'com.hunnit-beasts:doro-sdk'` + `settings.gradle` 에서
```groovy
includeBuild('../Doro') {
    dependencySubstitution { substitute module('com.hunnit-beasts:doro-sdk') using project(':sdk') }
}
```
(doro-blog 방식. 아티팩트 저장소 배포 설정 `maven-publish` 는 `sdk/build.gradle` 에 없다. [코드])

**전제 조건**
- **Java 25 툴체인 필수**(SDK 가 25 로 컴파일). Spring Boot 4.1.0(`starter-web`, `spring-aspects`), JJWT 0.12.6, gRPC 1.80.0(Spring Boot BOM 과 맞춰 `guard`·`sdk` 모두 1.80.0). [코드: `sdk/build.gradle`, `guard/build.gradle`]
- **Spring Security 와 무관하게 동작**한다. SDK 는 Spring Security 컨텍스트를 채우지 않는다. 함께 쓰면 두 인증 체계가 따로 논다.
- 자동설정(`DoroAutoConfiguration`)이 필터·AOP·gRPC 클라이언트·인자 해석기·기본 예외 어드바이스를 등록한다. 각 빈은 `@ConditionalOnMissingBean`.

### 4.1 `DoroJwtAuthFilter` / `DoroTokenVerifier` [코드]
- 검증 규칙(아래 서명·`exp`·`iss`·`aud`·`cid`·폐기 확인)은 **`DoroTokenVerifier`**(빈, `@ConditionalOnMissingBean`)에 있고, 필터는 토큰을 꺼내 이 검증기를 부르는 얇은 껍데기다. BFF 처럼 토큰을 다른 경로로 받는 코드도 같은 검증기를 주입받아 쓴다.
- 서블릿 필터, `FilterRegistrationBean`, **order = `HIGHEST_PRECEDENCE`**.
- **토큰 출처**: `Authorization: Bearer` 헤더가 우선. 헤더가 없을 때만 **쿠키**(`doro.iam.cookie-name`, 기본 빈 값 = 쿠키 읽지 않음)에서 읽는다.
- 검증: RS256 만 허용, **`exp` 필수**, 서명, 만료(시계 오차 `clock-skew-seconds` 기본 5초).
  - **`iss`**: `doro.iam.issuer-validation` = `OFF`(기본) | `WARN` | `ENFORCE`. 기본은 검증하지 않는다. 켜려면 `doro.iam.issuer` 를 IAM 의 `DORO_IAM_ISSUER` 와 같게 맞춘다.
  - **`aud`**: `doro.iam.audience` 가 **비어 있으면 `aud` 가 있는 토큰(예: OIDC id_token)을 거부**한다(토큰 혼동 방지). 값이 있으면 항상 ENFORCE — `aud` 에 그 값이 없는 토큰은 거부.
  - **`cid`**(OAuth 클라이언트 토큰): `doro.iam.oauth-client-ids` 에 **적힌 클라이언트의 토큰만** 통과한다(기본은 비어 있어 모두 거부). 일반 로그인 토큰(`cid` 없음)은 영향이 없다. 통과한 요청의 `DoroUser.clientId()` 로 클라이언트 토큰 여부를 구분한다(`isOAuthClientToken()`). 제3자 앱이 사용자 대신 받은 토큰이 서비스의 모든 보호 API 에서 사용자 본인의 권한으로 동작하지 않게 하려는 장치다.
- **세션 폐기 확인**(`doro.iam.revocation-check`: `OFF`(기본) | `WARN` | `ENFORCE`): 서명 검증 뒤 IAM `GET /api/v1/sessions/current` 에 같은 토큰으로 질의한다. 활성 응답은 `sid` 별로 `revocation-cache-seconds`(30초) 캐시, 폐기 응답은 토큰 `exp` 까지 캐시, 동시 조회는 합쳐진다(single-flight). IAM 에 닿지 못하면(연결 실패/타임아웃/5xx) `revocation-failure-backoff-seconds`(10초) 동안 재호출하지 않고 `revocation-fail-open`(기본 true) 정책을 적용한다. URL 은 `revocation-url`, 비우면 `jwks-uri` 의 origin + `/api/v1/sessions/current`. **ENFORCE 에서 폐기된 세션이면 익명 처리**(요청 자체는 막지 않음).
- **토큰이 없거나 검증에 실패해도 요청을 막지 않는다**(WARN 로그만). 사용자만 `anonymous` 로 남는다. → **인증 강제는 SDK 필터가 해주지 않는다.** 컨트롤러가 직접 `isAuthenticated()` 를 확인하거나 `@DoroGuard` 를 써야 한다.
- 사용자 정보는 `ThreadLocal`(`DoroUserContext`)에 담긴다(요청 종료 시 정리). 비동기 스레드로 넘어가면 사라진다.
- `X-Trace-Id`(안전한 형식만, 아니면 새 UUID)를 MDC `traceId` 로 넣고 응답 헤더에도 돌려준다.

### 4.2 `@CurrentDoroUser` [코드]
- 타입 `DoroUser`(record: `userId, email, sessionId, userIndex, role, clientId`), `UUID`, `String` 지원. `clientId` 는 OAuth 클라이언트 토큰(`cid`)이면 그 ID, 일반 로그인 토큰이면 null(`isOAuthClientToken()`).
- **비로그인이면 예외가 아니라 `anonymous`**(`userId == null`, `role == "ANON"`)가 주입된다. `UUID`/`String` 으로 받으면 **`null`**. NPE 주의.
- `DoroUser.isAuthenticated()`, `isAdmin()`, `isSuperAdmin()` 제공. `role` 은 **JWT 클레임 값**이다(Guard 를 거치지 않음, 최대 토큰 수명만큼 낡음).

### 4.3 `@DoroGuard` [코드]
```java
@DoroGuard("board_post:#postId#viewer")                       // 단축형: namespace:objectSpEL#relation
@DoroGuard(namespace="x", object="#id", relation="editor")    // 속성형
@DoroGuard(namespace="x", object="#id", relation="viewer", subject="#targetUserId") // 대리 검사
```
- 메서드와 **클래스** 모두에 붙일 수 있다(메서드의 `@DoroGuard` 가 우선).
- SpEL 변수: 메서드 **파라미터 이름**(`-parameters` 컴파일 옵션 필수), `#p0/#a0`, `#args`. `#` 로 시작하지 않는 표현식은 리터럴.
- **비로그인 + `subject` 미지정 → `DoroAccessDeniedException`**. SDK 기본 어드바이스(§4.6)가 **401**(비로그인)/**403**(권한 없음)으로 변환한다. 서비스가 같은 예외에 자체 `@ExceptionHandler` 를 두면 그쪽이 우선한다.
- `doro.guard.enabled=false` 이면 `@DoroGuard` 보호 대상 호출은 **fail-closed 로 거부**된다(예외 → 403).
- **프록시 기반 AOP**라 **같은 클래스 내부 호출(self-invocation)** 에는 적용되지 않고, **Spring 빈**의 메서드여야 한다(`new` 로 만든 객체·`private`/`final` 메서드는 가로채지 못한다. 안전하게 쓰려면 `public` 빈 메서드에 붙이고 다른 빈에서 호출한다). SDK 는 컴파일·로드 타임 위빙이 아니라 `@EnableAspectJAutoProxy`(`DoroAutoConfiguration`) 만 켜고 애스펙트(`@Around("@annotation(doroGuard)")`, 클래스 레벨은 `@within`)를 빈으로 등록한다. [코드: `DoroAutoConfiguration`, `DoroGuardAspect`; 프록시 한계 자체는 Spring AOP 일반 규칙이라 SDK 테스트로 따로 고정돼 있지는 않다]
- 애스펙트는 **`checkOrThrow()`** 를 쓴다(`12d6582` 이후 변경). 따라서 **Guard 장애는 거부(403)가 아니라 `DoroGuardUnavailableException` → 기본 어드바이스가 503 `GUARD_UNAVAILABLE`** 이 되고, `INVALID_ARGUMENT` 는 500 `GUARD_INVALID_REQUEST`, 서비스 토큰 오설정(`UNAUTHENTICATED`/`PERMISSION_DENIED`)은 500 `GUARD_MISCONFIGURED`(§4.6). 진짜 거부(`allowed=false`)만 403 이다. 서비스가 `DoroAccessDeniedException` 핸들러만 두면 `DoroGuardUnavailableException` 이 그 하위 타입이라 장애도 그쪽(403)으로 흘러가므로, 구분하려면 장애 예외 핸들러를 따로 둔다. [코드: `DoroGuardAspect`, `DoroGuardClient.classify`, `DoroExceptionHandlerAdvice`]

### 4.4 `DoroGuardClient` [코드]
- gRPC(plaintext `usePlaintext()`, TLS 없음), 호출당 **3초 deadline**. 호출마다 `X-Trace-Id`(MDC 의 traceId)를 전파하고, `doro.guard.service-token` 이 있으면 `x-doro-service-token` 메타데이터를 붙인다.
- 예외를 삼키는 계열(기존): `check` 는 예외 시 `false`(fail-closed), `writeTuple`/`deleteTuple` 은 예외 시 **`0` 반환**, `expand` 는 `null`. **반환값을 확인하지 않으면 DB 와 Guard 튜플이 조용히 어긋난다.**
- **예외를 던지는 계열(권장)**: `checkOrThrow`(실패 시 `DoroGuardUnavailableException`), `writeTupleOrThrow`/`deleteTupleOrThrow`(실패 시 `DoroGuardWriteFailedException`), `expandOrThrow`. 장애와 "진짜 거부"를 구분할 수 있다.
- `expand(namespace, objectId, relation)` → 접근 가능 주체 트리 JSON 문자열(Guard `Expand` RPC, §6.4).
- 튜플 쓰기와 서비스 DB 저장은 **하나의 트랜잭션이 아니다.** 저장 후 `writeTuple` 이 실패하면 고아 상태가 될 수 있다.

### 4.5 설정 (`application.yaml`) [코드: `DoroProperties`]
```yaml
doro:
  iam:
    jwks-uri: http://localhost:8080/.well-known/jwks.json   # 기본값은 이 로컬 주소. 운영은 IAM 주소로 바꿀 것
    issuer: https://auth.doro.local        # issuer-validation 이 OFF 면 쓰이지 않음
    issuer-validation: OFF                 # OFF | WARN | ENFORCE
    audience: ""                           # 비어 있으면 aud 있는 토큰 거부
    oauth-client-ids: []                   # 받아 줄 OAuth 클라이언트 ID. 비어 있으면 cid 토큰(제3자 앱이 받은 토큰) 전부 거부
    cookie-name: ""                        # 비어 있으면 쿠키 미사용
    clock-skew-seconds: 5
    jwks-prefetch: true                    # 기동 시 백그라운드 사전 조회(실패해도 기동 계속)
    revocation-check: OFF                  # OFF | WARN | ENFORCE
    revocation-url: ""                     # 비우면 jwks-uri origin + /api/v1/sessions/current
    revocation-cache-seconds: 30
    revocation-timeout-millis: 2000
    revocation-fail-open: true
    revocation-failure-backoff-seconds: 10
    # 아래 둘은 DoroProperties 가 아니라 탈퇴 동기화 모듈(§4.7)이 @Value 로 읽는다
    internal-url: http://localhost:28080   # IAM 내부 API 주소(Docker 내부 주소). 기본값은 로컬 개발용
    internal-token: ""                     # IAM 의 DORO_IAM_INTERNAL_TOKENS 에 '서비스명:토큰' 으로 등록한 값. 환경변수로만, 로그에 남기지 말 것
  guard:
    grpc-host: localhost
    grpc-port: 9090
    enabled: true
    service-token: ""                      # Guard 가 ENFORCE 이면 필수. 값은 로그/문서에 남기지 말 것
  web:
    exception-handler: true                # false 면 기본 예외 어드바이스를 등록하지 않음
```
- **JWKS 캐시**: 알 수 없는 `kid` 는 동기 조회(30초 쿨다운으로 증폭 방지), 캐시된 키는 **10분 TTL 후 백그라운드 갱신**(요청 스레드를 막지 않음), JWKS 에서 사라진 `kid` 는 캐시에서 제거, 비어 있거나 파싱 실패한 JWKS 는 장애로 보고 기존 키 유지. 키 로테이션/교체가 서브서비스에 자동 반영된다.
- doro-blog 은 추가로 `doro.guard.http-url`(스키마 등록용 REST)과 `service-token` 을 자체 속성으로 쓴다(SDK 속성 아님). [코드: doro-blog `application.yaml`]

### 4.6 기본 예외 어드바이스 `DoroExceptionHandlerAdvice` [코드]
`@RestControllerAdvice(@Order LOWEST)`. 응답은 `{success:false, code, message, status}`:
- `DoroAccessDeniedException` → 비로그인 **401** `UNAUTHORIZED` / 로그인 **403** `ACCESS_DENIED`(사용자·객체 ID 는 응답에 싣지 않음)
- `DoroGuardUnavailableException` → **503** `GUARD_UNAVAILABLE`
  - `checkOrThrow`/`expandOrThrow` 는 gRPC 상태로 구분한다: `UNAVAILABLE`·`DEADLINE_EXCEEDED`·`RESOURCE_EXHAUSTED`·연결 실패는 위 503. `INVALID_ARGUMENT` → `DoroGuardInvalidRequestException`(장애 예외와 무관) → **500** `GUARD_INVALID_REQUEST`(호출 코드의 입력 버그). `UNAUTHENTICATED`/`PERMISSION_DENIED`(서비스 토큰 오설정) → `DoroGuardMisconfiguredException`(`DoroGuardUnavailableException` 의 하위 타입이라 기존 503 핸들러도 그대로 잡음) → 기본 어드바이스 **500** `GUARD_MISCONFIGURED` + ERROR 로그.
  - 기동 시 약한 기본 설정(issuer 검증 OFF, 폐기 확인 OFF/fail-open, 평문 HTTP JWKS)이 켜져 있으면 WARN 한 줄로 요약한다(기본값은 바꾸지 않음).

### 4.7 BFF 로그인·탈퇴 동기화 모듈 (`sdk/.../bff`, `sdk/.../sync`) [코드: `DoroBffAutoConfiguration`, `DoroDeletedUsersAutoConfiguration`, `BffSessionAuthFilter`, `DeletedUsersProperties`]
둘 다 **서비스가 빈을 등록해야만 켜진다**(없으면 아무 영향 없음). 상세 규칙·서비스가 할 일은 [SDK_BFF.md](SDK_BFF.md), 게이트웨이 모드는 [BFF.md](BFF.md).
- **BFF 로그인**: `BffProperties` + `BffSessionStore` + `BffLoginAttemptStore` 빈 **셋 다** 있을 때만 켜진다. `GET /api/v1/bff/login`·`/callback`, `POST /api/v1/bff/logout`(CSRF 헤더 필요)는 SDK 가 제공하고 `GET /api/v1/bff/session` 은 서비스가 만든다. 세션 쿠키 → `DoroUser` 변환 필터(`BffSessionAuthFilter`, order `HIGHEST_PRECEDENCE+20`)가 있어 **쿠키 요청에도 `@CurrentDoroUser`/`@DoroGuard` 가 Bearer 와 똑같이 동작**한다(Bearer 로 이미 인증된 요청은 건드리지 않음). 쿠키로 상태를 바꾸는 요청은 커스텀 CSRF 헤더(기본 `X-Doro-Csrf`, 서비스별로 `csrf-header` 설정) + (있다면) `Origin` 일치가 필요해 없으면 JSON 403 `AUTH-403-03`. 토큰은 서비스 DB 에 AES-GCM 으로 암호화(`session-encryption-key`, base64 32바이트) 저장. 기본 클라이언트 요청 스코프 `openid profile email`, 세션 TTL 30일, 갱신 여유 60초. **IAM 장애·429 는 로그아웃이 아니다**(그 요청만 익명, IAM 이 400/401 로 명시 거부할 때만 세션 삭제).
- **gateway 모드 서비스**는 위 빈들을 등록하지 않는다(BFF 는 `doro-bff` 가 하고 서비스는 Bearer 만 검증, 사용자 행은 로그인 후 첫 `/api/v1/bff/session` 조회에서 만든다). [코드: `docs/BFF.md`]
- **탈퇴 동기화**: `DeletedUsersProperties` + `DeletedUserHandler` + `SyncCursorStore` 빈이 있으면 `GET {doro.iam.internal-url}/internal/v1/deleted-users` 를 주기(기본 1시간, 첫 실행 2분 뒤, 페이지 200, 겹침 10분)로 가져와 핸들러를 부른다. 서비스에 `@EnableScheduling` 이 있어야 돈다. 핸들러가 예외를 던지면 그 실행은 실패로 끝나고 다음 주기에 같은 사용자부터 이어서 처리한다. `doro.iam.internal-token` 이 비어 있으면 헤더를 보내지 않아 IAM 이 ENFORCE 일 때 401 이다.
- **`doro-bff` 모듈 구조** [코드: `bff/.../BffServiceRegistry`, `AuthRequestController`, `BffGatewayController`, `BffStorage`, `BffCleanup`, `BffSyncConfig`, `V1__bff_init.sql`, `application.yaml`, `FlywayConfig`]:
  - **저장소는 PostgreSQL(JPA, `ddl-auto: validate`) 뿐**이다(Redis 미사용). DB `doro_bff`, Flyway 이력 `bff_schema_history`, 마이그레이션은 `V1__bff_init.sql` 하나로 테이블 3개: `auth_sessions`(`service`·`session_hash` UNIQUE·`user_id`·`access_token_enc`·`access_expires_at`·`refresh_token_enc`·`created_at`·`last_used_at`·`expires_at`=로그인부터의 절대 만료), `login_attempts`(`state_hash` PK·`service`·`code_verifier_enc`·`return_path`≤500·`browser_hash`·`expires_at`), `iam_sync_state`(`name` PK·`cursor_at` — 탈퇴 동기화 커서). 세션 ID 와 `state` 는 **해시로만** 저장(DB 가 유출돼도 쿠키를 만들 수 없음), 토큰과 PKCE verifier 는 `BFF_SESSION_KEY`(AES-256-GCM, base64 32바이트, 기본값 없음 — 없으면 기동 거부)로 암호화해 저장한다. `state` 는 한 번만 쓰이고(삭제된 행이 1일 때만 인정) 다른 서비스의 `state` 는 쓸 수 없다.
  - **쿠키 모델**: 세션 쿠키와 '로그인 진행 중' 쿠키 모두 `HttpOnly`, `SameSite=Lax`, `Secure`(`BFF_COOKIE_SECURE`, 기본 true). 세션 쿠키 Path 는 서비스 설정 `cookie-path`(blog `/`, party `/party`, games `/games`), 수명은 세션 TTL(기본 30일); 로그인 쿠키는 `<web-base-path>/api/v1/bff` 경로, 수명 10분. 이름은 `doro_<서비스>_session`/`doro_<서비스>_login`, CSRF 헤더 `X-Blog-Csrf`/`X-Party-Csrf`/`X-Games-Csrf`.
  - **설정 키**: 공유 값은 `doro.bff.{authorize-url, token-url, revoke-url, session-encryption-key, cookie-secure, session-ttl, login-attempt-ttl, refresh-margin, http-connect-timeout, http-read-timeout, deleted-sync.*}`, 서비스별 값은 `doro.bff.services.<이름>.{client-id, cookie-name, login-cookie-name, cookie-path, web-base-path, redirect-uri, csrf-header, return-path-relative-to-web-base}`. 서비스 이름은 `[a-z][a-z0-9-]{0,31}`. 기동 때 서비스가 하나도 없거나 필수 값이 비었거나 서비스 사이에 `client-id`·쿠키 이름이 겹치면 기동 실패(한 서비스의 세션이 다른 서비스에서 통하지 않게). 토큰 검증은 SDK `DoroTokenVerifier`(`doro.iam.oauth-client-ids` 기본 `doro-blog,doro-party,doro-games`, 폐기 확인 `ENFORCE`).
  - **엔드포인트**: `GET /bff/{service}/login`·`/callback`, `POST /bff/{service}/logout`(SDK `BffController` 를 서비스마다 한 벌) 과 게이트웨이용 `ANY /internal/auth`. `/internal/auth` 는 `X-Doro-Service`(알 수 없으면 400)와 `X-Original-Method` 를 받아 ① 세션 쿠키 없음 → 토큰 없는 204 ② 쿠키가 있는데 `X-Original-Method` 가 없으면 400(CSRF 판정 불가라 로그인으로 인정하지 않음), 안전하지 않은 메서드(GET/HEAD/OPTIONS 외)에 CSRF 헤더가 없거나 `Origin` 이 공개 origin 과 다르면 403 ③ 세션 확인·필요시 갱신 성공 → 204 + `X-Doro-Access-Token`, 실패(만료·폐기·IAM 장애) → 토큰 없는 204 로 답한다. 이 경로에는 **자체 인증이 없다**: 게이트웨이의 `internal` location 에서만 부르고, 게이트웨이에 외부 location 을 만들거나 호스트에 노출하지 말 것(compose 는 `127.0.0.1:8085` 로만 바인딩).
  - **정리**: `BffCleanup` 이 시작 5분 뒤부터 1시간마다 만료된 세션·로그인 시도를 지운다. 탈퇴 동기화(`doro.bff.deleted-sync.enabled`, 환경변수 `BFF_DELETED_SYNC_ENABLED` 기본 true)는 IAM 의 영구 탈퇴 사용자의 BFF 세션을 모든 서비스에서 지우고(`deleteAllSessionsByUserId`, 멱등) 커서를 `iam_sync_state` 에 DB 로 저장한다. 호출 토큰(`BFF_IAM_INTERNAL_TOKEN` → 컨테이너 `DORO_IAM_INTERNAL_TOKEN`)이 없으면 동기화는 실패 로그만 남긴다.
  - **embedded 모드 서비스**는 같은 구조의 테이블(`auth_sessions`, `login_attempts`)을 **자기 서비스 DB** 에 갖는다(`service` 열 없음; blog `V8__bff_sessions.sql`·`V11__login_attempt_browser_binding.sql`, party `V2`·`V10`, games `V2`·`V5`). gateway 모드로 바꿔도 이 테이블은 남는다.
- 두 모듈 모두 `DoroTokenVerifier` 빈에 기대므로, BFF 가 받은 `cid` 토큰이 통과하려면 `doro.iam.oauth-client-ids` 에 그 서비스의 클라이언트 ID 가 있어야 한다(§4.1).

---

## 5. OAuth 2.1 / OIDC — 실제 동작

**인가 코드 + PKCE(S256) 플로우가 구현되어 있다.** 공개 클라이언트(`none`)와 기밀 클라이언트(`client_secret_basic`, `client_secret_post`)를 지원한다. 기밀 클라이언트는 PKCE 에 더해 토큰·폐기 요청에서 시크릿을 내야 한다. [코드: `OAuth2Controller`, `OAuth2Service`, `OAuthClientRegistry`]

### 5.1 플로우
1. 클라이언트 앱이 사용자를 `GET {origin}/oauth2/authorize?response_type=code&client_id=&redirect_uri=&code_challenge=&code_challenge_method=S256&scope=&state=&nonce=` 로 보낸다.
2. **Bearer 없는 브라우저 요청**: IAM 은 `client_id`/`redirect_uri` 를 먼저 검증한다(실패 시 **400 JSON, 절대 리다이렉트하지 않음** — 오픈 리다이렉트 방지). 나머지 파라미터 오류는 `redirect_uri` 로 RFC 6749 오류 리다이렉트(`error`, `error_description`, `state`). 정상이면 **302 → `doro.oauth.consent-url`(기본 `/oauth2/consent`) + 원래 쿼리**. 게이트웨이는 정확 일치 `= /oauth2/consent` 를 포털로 보낸다.
3. **포털 동의 화면(`OAuthConsentPage`)**: 로그인 전이면 검증된 요청을 `sessionStorage` 에 보관(10분 유효)하고 로그인 후 이 화면으로 복귀. 승인하면 **Bearer 를 붙여** `GET /oauth2/authorize` 를 호출 → 응답 `{success:true, data:{code, state}}`(JSON) → 포털이 `redirect_uri?code=…&state=…` 로 이동. (Bearer 있는 호출은 이 JSON 계약이다.) 활성 세션이 아닌 토큰이면 거부.
4. 클라이언트 서버/앱이 `POST /oauth2/token` 으로 코드를 교환(코드는 **1회용**, 기본 5분 유효, `redirect_uri`·`client_id` 가 발급 때와 같아야 하고 PKCE 검증). 재사용된 코드는 경고 로그만 남기고 이미 발급된 토큰은 폐기하지 않는다.

### 5.2 엔드포인트
| 경로 | 설명 |
|---|---|
| `GET /oauth2/authorize` | 위 플로우. `response_type=code` 만, `code_challenge` 필수(43자 base64url), `code_challenge_method` 는 생략하거나 `S256`, `state`≤512자, `nonce`≤256자 |
| `POST /oauth2/token` | **`application/json`**(camelCase `grantType, code, redirectUri, clientId, codeVerifier, refreshToken, scope`, 응답은 `ApiResponse<TokenResponse>`) 또는 **`application/x-www-form-urlencoded`**(RFC 6749 snake_case `grant_type, code, redirect_uri, client_id, code_verifier, refresh_token, scope`, 응답은 `access_token, token_type, expires_in, refresh_token, [scope], [id_token]`, 오류는 `{error, error_description}`). form 은 파라미터를 **본문으로만** 받고 중복·쿼리 문자열은 거부. 응답에 `Cache-Control: no-store` |
| `POST /oauth2/revoke` | RFC 7009 토큰 폐기. **`application/x-www-form-urlencoded` 만**(`token`, `client_id`, 기밀 클라이언트는 `client_secret` 또는 Basic). 파라미터는 본문으로만. 그 클라이언트의 OAuth 세션에 속한 리프레시 토큰이면 IAM 세션을 끝내고(킬스위치 포함) 토큰을 무효화한다. **알 수 없는 토큰·다른 클라이언트의 토큰·일반 로그인 세션의 토큰은 아무 일도 없이 200**(존재 여부 비노출). 토큰 엔드포인트와 같은 IP 요청 제한 버킷. BFF 의 로그아웃이 사용한다 |
| `GET /oauth2/client-info?client_id=` | 로그인 필요. 등록·활성 클라이언트만 `{clientId, name, firstParty}`, 아니면 404 `{error:"invalid_client"}`(포털 동의 화면용, §5.3) |
| `GET /oauth2/userinfo` | Bearer OAuth 액세스 토큰. `sub, email, email_verified(항상 false), name, picture(http(s) URL 만)`. 세션이 유효해야 함 |
| `GET /.well-known/openid-configuration` | 메타데이터. **`issuer` 및 엔드포인트 URL 이 `DORO_IAM_ISSUER` 값으로 만들어진다**(§9 참고) |

- **grant**: `authorization_code`, `refresh_token`(둘 다 지원). 리프레시 grant 는 **그 `client_id` 의 OAuth 세션에 속한 토큰만** 허용하고(다른 클라이언트·일반 로그인 토큰은 소모하지 않고 거부), 기존 RTR(재사용 탐지 포함)을 쓴다. 리프레시 응답에는 `id_token` 이 없다. **정지(`ACCOUNT_SUSPENDED`)·없는 사용자**는 form 요청에서 RFC 오류 `invalid_grant`("사용자 계정을 사용할 수 없습니다.")로 응답한다(JSON 요청은 IAM `ErrorResponse`). 세션이 절대 수명(90일)에 닿으면 갱신이 거부된다(§2.3).
- **`id_token`**: `openid` 스코프가 승인됐을 때만 발급(교환 응답에서). 스코프: `openid`, `profile`, `email`. `scope` 를 생략하면 빈 스코프(= `id_token` 없음, 클레임 없음).
- **세션/토큰**: 코드 교환은 새 세션을 만든다(IP 마커 `OAuth2`, UA `OAuth2 PKCE Flow: <client_id>`). **서비스(클라이언트)별로 한 사용자가 3개 기기까지** 동시에 로그인해 둘 수 있고(`DORO_IAM_SESSION_MAX_OAUTH_PER_CLIENT`, 기본 3), 4번째 기기가 로그인하면 그 서비스에서 **가장 오래 쓰지 않은 세션**(마지막 활동 기준)만 종료된다. 다른 서비스·포털 세션은 영향이 없다. 1 로 두면 예전처럼 서비스당 하나만 유지한다(새 로그인이 이전 세션 대체). 사용자당 전체 활성 세션 상한(기본 20)에는 포함된다. OAuth 액세스 토큰은 `role=USER` + `cid`(§3).
- **인가 코드 저장소**: `doro.oauth.code-store` = `auto`(기본: 기동 시 Redis 에 닿으면 Redis, 아니면 WARN 후 메모리) | `redis` | `memory`. 메모리 모드는 재시작/다중 인스턴스에서 코드가 유실되고 `max-pending-authorization-codes`(10000) 초과 시 429. `auto` 가 기동 시 Redis 에 닿지 못하면 **재시작 전까지 메모리**를 쓴다.

### 5.3 클라이언트 등록과 모드
- 테이블 `oauth_clients`(Flyway **V6**, V7). 등록: `POST /api/v1/admin/oauth/clients` `{name, redirectUris[1~10], scopes?, clientId?, firstParty?, confidential?}`(ADMIN + Guard `system:doro#admin`). `redirect_uri` 는 **https 만**(loopback 호스트는 http 허용), 와일드카드/fragment/userinfo/`..` 세그먼트 불가, ≤500자. `clientId` 생략 시 서버가 생성(`[A-Za-z0-9._~-]{1,100}`). `confidential: true` 이면 `client_secret`(`dcs_…`)을 만들어 **응답에 한 번만** 돌려준다(DB 는 SHA-256 해시만 보관, Flyway V12, 목록 조회에는 없음). `POST .../{clientId}/secret` 은 시크릿 발급·회전(이전 시크릿 즉시 무효, 공개 클라이언트가 기밀로 바뀜). 토큰(code/refresh)·폐기 요청은 Basic 헤더 또는 본문 `client_secret`(JSON `clientSecret`)으로 인증하며 둘을 섞으면 400, 틀리거나 없으면 401 `invalid_client` 이고 인가 코드는 소비되지 않는다. `DELETE .../{clientId}` 는 **소프트 삭제**(`is_active=false`). 이후 요청은 `WARN`/`ENFORCE` 에서 거부되고, `OFF` 모드는 레지스트리를 조회하지 않아 영향이 없다 [코드: `OAuthClientRegistry.lookup`].
- `redirect_uri` 는 **문자열 정확 일치**만 인정한다(정규화·부분 일치 없음).
- 클라이언트에 `firstParty: true` 를 주면(관리자 등록 시, 테이블 컬럼 `first_party`, Flyway **V7**) **자사 서비스**로 표시된다. 포털 동의 화면은 이 표시가 있는 클라이언트에는 동의를 묻지 않고 로그인 직후 바로 인가 코드를 요청한다(`GET /oauth2/client-info?client_id=` 로 확인, 로그인 필요, 등록·활성 클라이언트만 응답). 제3자 앱은 기본값(`false`)이라 기존처럼 동의 화면을 거친다. `redirect_uri` 의 정확 일치 검증은 자사 앱에도 똑같이 적용된다.
- `doro.oauth.client-registry-mode`(`DORO_OAUTH_CLIENT_REGISTRY_MODE`, 기본 **`ENFORCE`**):
  - `OFF`: 레지스트리 미사용, 환경변수 허용 목록(`DORO_OAUTH_ALLOWED_REDIRECT_URIS`, 쉼표 구분, 정확 일치)만 사용.
  - `WARN`: **등록된 클라이언트는 레지스트리 규칙**(등록한 redirect_uri/scope 만), **미등록 `client_id` 는 환경변수 허용 목록으로 폴백**(경고 로그).
  - `ENFORCE`: 미등록 `client_id` 는 `invalid_client` 로 거부.
  - 등록돼 있지 않고 허용 목록도 비어 있으면(기본) 인가 요청은 모두 거부된다.
- 서비스가 OAuth 로 붙는 설계가 필요하면: ① 관리자가 클라이언트 등록 ② `ENFORCE` 로 올릴지 운영자와 협의 ③ PKCE(S256) 구현 ④ 받은 액세스 토큰을 서브서비스 API 에 쓰되 권한은 Guard 로 판정.

### 5.4 한계 [코드]
고정 스코프만(스코프별 `userinfo` 응답, 동의 이력 저장 없음. 클라이언트 시크릿 인증은 구현됨), `email_verified` 는 항상 `false`, 인가 코드 재사용 시 토큰 폐기 미구현.

---

## 6. Guard — 실제 규격

### 6.1 서비스 인증 (OFF / WARN / ENFORCE)
`doro.guard.security.mode`(`DORO_GUARD_SECURITY_MODE`, 기본 **`OFF`**) — 설정 없이는 **인증이 없다.** [코드: `ServiceAuthProperties`, `ServiceTokenFilter`, `ServiceTokenServerInterceptor`]
- 토큰은 REST 헤더 `X-Doro-Service-Token`, gRPC 메타데이터 `x-doro-service-token`. 모든 호출자가 토큰을 보내도록 배포한 뒤 `OFF → WARN → ENFORCE` 로 올린다. `WARN` 은 로그만, `ENFORCE` 는 REST 401(`UNAUTHORIZED`)/gRPC `UNAUTHENTICATED`.
- **공유 토큰** `DORO_GUARD_SERVICE_TOKEN`(전환 기간용, 모든 권한 보유). **호출자별 토큰** `DORO_GUARD_SERVICE_TOKENS=auth:<토큰>,blog:<토큰>[:schema-write]`: 이름은 `[a-z][a-z0-9-]{0,31}`, 토큰 32자 이상, 이름/토큰 중복·`shared` 예약, 형식 오류면 **기동 실패**. 비교는 상수 시간.
- **권한(scope)**: `POST /api/v1/guard/schema`(스키마 전체 교체)는 **`schema-write`** 가 있는 호출자(또는 공유 토큰)만 가능. 없으면 ENFORCE 에서 403 `FORBIDDEN`(WARN 에서는 경고만). 운영에서는 스키마를 등록하는 서비스만 갖는다. gRPC 에는 스키마 쓰기가 없다.
- **네임스페이스 제한**: `DORO_GUARD_SERVICE_TOKENS=blog:<토큰>:schema-write:blog_*` 처럼 4번째 칸(`+` 로 여러 개, 정확한 이름 또는 `접두사*`, 권한 칸은 비우면 `::`)에 소유 네임스페이스를 주면 그 호출자는 객체 네임스페이스가 그 안인 튜플만 쓰기/삭제하고, 스키마는 소유하지 않은 타입이 활성 스키마와 같을 때만 등록할 수 있다. 위반은 ENFORCE 에서 REST 403 `NAMESPACE_FORBIDDEN` / gRPC `PERMISSION_DENIED`(배치 전체 거부), WARN 은 로그만. 네임스페이스 없는 호출자(IAM)·공유 토큰은 무제한, 호출자끼리 겹치면 기동 실패, 조회·체크는 제한 없음. [코드: `CallerNamespaceGuard`, `ServiceAuthProperties.ownsNamespace`, `ServiceTokenServerInterceptor.CALLER`]
- 호출자별 토큰이 설정된 뒤에도 공유 토큰으로 들어오는 호출은 경고(`Guard call with the deprecated shared service token`)가 남는다(정리 대상).
- REST 보호 범위: 서버가 퍼센트 디코딩·정규화한 경로로 판정하며(`/%61pi/v1/guard/...` 같은 인코딩 경로로 우회할 수 없다), **공개 허용 목록(`/actuator`, `/swagger-ui`, `/v3/api-docs`, `/error`)에 없는 요청은 전부 토큰 검사 대상**이다. 즉 이전의 "`/api/v1/guard/**` 만"이 아니다. 컨테이너 헬스는 `/actuator/health` 를 쓸 것(`HealthController` 의 `/health` 는 허용 목록에 없어 `WARN`/`ENFORCE` 에서 토큰이 필요하다). [코드: `ServiceTokenFilter`] **gRPC·REST 모두 TLS 가 없다** → 신뢰 네트워크 안에서만 쓰고 **절대 외부 노출 금지**. 게이트웨이 nginx 는 Guard 를 프록시하지 않는다(확인함). 호스트 포트 게시는 기본 `127.0.0.1` 이다(`GUARD_BIND`).

### 6.2 REST (`:8081`)
경로는 `/api/v1/guard/**` 이다. 응답은 `{success, data, timestamp}` 봉투, 오류는 IAM 과 같은 `ErrorResponse` 형식(`code` 는 enum 이름). 필드 길이 제한: `namespace/relation/subjectNamespace/subjectRelation` ≤64, `objectId/subjectId` ≤128. REST 와 gRPC 가 같은 한계를 쓴다. **REST 에는 Expand 엔드포인트가 없다**(gRPC 전용).

| 메서드·경로 | 요청 | 응답 `data` |
|---|---|---|
| `POST /api/v1/guard/check` | `{namespace, objectId, relation, subjectNamespace, subjectId, subjectRelation?}` | `{allowed, depth, reason}` (`reason`: `ACCESS_GRANTED`/`ACCESS_DENIED`/`L1_CACHE_HIT`) |
| `POST /api/v1/guard/tuples` | `[ {…튜플…} ]` | `{writtenCount}` |
| `DELETE /api/v1/guard/tuples` | 위와 동일한 배열(본문 있는 DELETE) | `{deletedCount}` |
| `GET /api/v1/guard/schema` | 없음 | **DSL 문자열** (`data` 가 곧 텍스트) |
| `POST /api/v1/guard/schema` | **JSON** `{"dsl": "..."}` | `{version, dsl, active}` |

- 입력 상한: 튜플 쓰기/삭제는 요청당 **1000개**(REST·gRPC 공통, 초과 시 400 / `INVALID_ARGUMENT`), 스키마 DSL 은 **100,000자**. [코드: `FieldLimits`]
- 튜플 쓰기는 **멱등**이고 같은 배치 안 중복은 합쳐진다(이미 있으면 건너뜀, `writtenCount` 에 안 잡힘). 직접 튜플은 DB 부분 유니크 인덱스(Flyway `db/vendor/postgresql/V3`)가 중복을 막는다.
- gRPC `doro.guard.v1.GuardService`: `Check`, `WriteTuples`, `DeleteTuples`, `Expand`. 오류는 4xx 성격이면 `INVALID_ARGUMENT`, 그 외 `INTERNAL`. [코드: `doro_guard.proto`, `GuardGrpcService`]
- 헬스: `/actuator/health`(서비스 토큰 불필요), `/health`(서비스 인증이 켜져 있으면 토큰 필요, §6.1).
- Flyway: 시작 때 `migrate()` 만 하고 `repair()` 는 하지 않는다(체크섬 불일치·실패한 마이그레이션은 기동 실패로 드러난다). **`guard`·`auth`·`bff` 세 모듈의 `FlywayConfig` 모두 같다**(`baselineOnMigrate` 기본 true·`baselineVersion=0`, 이력 테이블 `guard_schema_history`/`auth_schema_history`/`bff_schema_history`, JPA `entityManagerFactory` 가 `flyway` 빈에 의존). 차이는 `guard` 만 `locations` 의 쉼표 분리와 `{vendor}` 치환을 직접 하는 것(Spring Boot 자동 구성이 없어서). `repair` 가 필요하면 운영자가 Flyway CLI 로 직접 한다. [코드: 세 `FlywayConfig`]

### 6.3 스키마는 **전역 단일 버전**이고 등록은 **전체 교체**다 🔴
[코드: `SchemaService.registerSchema`]

- 활성 스키마는 Guard 전체에 **하나**다. `POST /schema` 는 보낸 DSL 로 **통째로 교체**한다(새 버전 = max+1 로 저장, 이전 버전은 비활성). 병합하지 않는다.
- 따라서 **자기 서비스 타입만 POST 하면 다른 서비스와 IAM 의 스키마가 사라진다.** `system` 타입이 사라지면 IAM 의 관리자 인가(`manage_roles`, `can_reset_2fa`, `admin`)가 전부 거부된다.
- **올바른 절차 (doro-blog `BlogSchemaInitializer` 방식)**:
  1. `GET /api/v1/guard/schema` 로 활성 DSL 을 받는다(`data` 필드).
  2. 자기 타입이 이미 있는지 확인한다(`contains("type blog_post")` 식).
  3. 없으면 `기존 DSL + "\n\n" + 내 DSL` 을 `POST {"dsl": ...}` 한다. **스키마 쓰기 권한(`schema-write`)이 있는 호출자 토큰**이어야 한다(Guard 가 ENFORCE 일 때).
- 남은 위험: **읽기-수정-쓰기 경합** — 두 서비스가 동시에 부팅하면 한쪽 변경이 유실될 수 있다(UNIQUE(version) 충돌은 서버가 최대 3회 재시도 후 409 `SCHEMA_CONFLICT`). 서비스 단위 스키마 등록 API 는 **없다**.
- **다중 인스턴스**: 각 인스턴스가 DB 의 활성 스키마 버전을 주기적으로 확인해(`DORO_GUARD_SCHEMA_REFRESH_SECONDS`, 기본 30초, 0=끔) 다른 인스턴스의 변경을 반영한다. 스키마가 바뀌면(등록 커밋 후/갱신 시) 인가 캐시를 비운다.
- **검증 모드** `DORO_GUARD_VALIDATION_MODE`(기본 **`WARN`**): 스키마 DSL(이해 못 한 줄, 선언되지 않은 타입 이름을 가리키는 직접 항 — 오타·전방 참조·괄호)과 **튜플**(스키마에 선언되지 않은 타입/릴레이션)을 검사한다. `WARN` 은 로그만 남기고 통과, `ENFORCE` 는 **400**(`INVALID_SYNTAX`/`INVALID_TUPLE`)으로 거부, `OFF` 는 검사 안 함. 운영에서는 로그를 확인한 뒤 `ENFORCE` 로 올리는 것을 권장.
- 워크스페이스 룰은 스키마 변경을 "정규 버전 관리"로 하라고 하므로, 새 타입의 `.doro` 파일을 서비스 저장소에 두고(doro-blog 의 `blog-schema.doro`) 위 절차로 등록한다. 시작 시 DB 에 활성 스키마가 없으면 클래스패스 `schema.doro` 로 시작한다.
- **네임스페이스는 서비스 접두사를 붙일 것**(`blog_post`, `sebi_bill`). 기본 스키마가 이미 `user`, `group`, `system`, `folder`, `document` 를 쓴다.

### 6.4 DSL 문법 — 이 파서가 실제로 받아들이는 것
[코드: `DslParser`, `CheckEngine`] **줄 단위 파서**다. 표준 Zanzibar DSL 이나 다른 문서의 예제를 그대로 쓰면 깨진다.

```
# 주석 (# 또는 // 로 시작하는 줄만. 줄 끝 주석 불가)
type blog_post {
  relation author: user
  relation editor: author
  relation viewer: author | editor | series#viewer
}
```

**규칙**
1. `type <이름> {` 로 열고 `}` 한 줄로 닫는다. `relation <이름>: <식>` 은 **반드시 한 줄**.
2. 연산자: `|`(합집합), `&`(교집합, **양옆 공백 필수**), `-`(차집합, **양옆 공백 필수**).
3. **괄호 `( )` 미지원.** `(member & nda_signed) - blocked` 는 깨진다(`"(member"` 라는 직접 항으로 해석 — `WARN` 모드면 "선언되지 않은 이름" 경고가 남는다).
4. **`&` 와 `|` 를 한 식에 섞지 마라.** 우선순위 처리가 없다(`-` → `&` → `|` 순으로 문자열을 자른다). `-` 는 왼쪽에 `|`/`&` 를 둘 수 있으나 오른쪽은 단일 항만 신뢰할 것.
5. **릴레이션을 사용하기 전에 선언하라.** `editor` 가 `viewer` 보다 **위에** 있어야 `viewer: editor | ...` 의 `editor` 가 "같은 객체의 다른 릴레이션"으로 해석된다. 뒤에 선언하면 무의미한 직접 항으로 취급돼 **항상 false**(검증 모드가 경고/거부).
6. `x#y` 형태의 항은 항상 **TTU** 로 해석된다: "현재 객체의 `x` 릴레이션 튜플이 가리키는 객체에서 `y` 를 검사".

**이 엔진의 특이한 점 — 반드시 알 것**
- **직접 튜플은 스키마를 거치지 않고 매칭된다.** `Check` 는 먼저 튜플 테이블에서 `(namespace, objectId, relation, subject)` 가 정확히 있는지 본다. **스키마에 없는 타입/릴레이션이라도 튜플이 있으면 `true`** 다. 이 평가 규칙은 검증 모드와 무관하다(검증은 쓰기 시점의 경고/거부일 뿐, 기본 `WARN` 이라 오타 튜플은 로그만 남기고 저장된다).
- `relation author: user` 같은 **타입 제약은 강제되지 않는다**. `viewer: author | user` 의 `user` 는 "모든 사용자"가 **아니다**(무의미한 항).
- 그룹(userset) 멤버십은 **튜플 쪽**에서 처리한다: `doc:1#viewer@group:eng#member` 같은 튜플(`subjectRelation="member"`)을 쓰면 엔진이 그룹 멤버를 재귀 검사한다. 스키마의 `group#member` 항은 TTU 로 해석되어 사실상 동작하지 않는다(기본 스키마의 `system#admin`, `group#member` 항도 마찬가지).
- 최대 깊이 32, 순환 방어(방문 집합). 깊이 초과·순환은 `false` 이며, **잘려서 나온 결과는 캐시하지 않고** 차집합(`-`)의 제외 쪽이 잘리면 fail-closed(거부)로 처리한다.
- **결과 캐시**: Caffeine **L1 인메모리**(인스턴스 로컬), 기본 **TTL 60초·최대 5만 건**(`DORO_GUARD_CACHE_TTL_SECONDS`, `DORO_GUARD_CACHE_MAX_SIZE`, TTL 0 이면 끔). 허용/거부 모두 캐시. 튜플 쓰기/삭제는 **커밋 후** 전체 무효화하고, 무효화 세대가 바뀐 평가 결과는 캐시에 넣지 않는다. **다른 인스턴스의 튜플 변경은 최대 TTL 만큼 늦게 보인다.** Redis L2 캐시는 없다.
- **Expand** (gRPC 전용): 대상 `객체#릴레이션` 에 접근 가능한 주체를 JSON 트리(`object`, `type: leaf|union|intersection|difference|computed|ttu`, `subjects`, `children`)로 전개. 직접 튜플 → userset 주체 → 스키마 표현식 순서, 최대 깊이/노드 상한(`expand-max-nodes` 기본 5000)에 걸리면 루트에 `"truncated": true`.

### 6.5 기본 스키마 (`guard/src/main/resources/schema.doro`)
`user`(manager, super_manager, can_reset_2fa), `group`, `system`(super_admin, admin, auditor, manage_roles), `folder`, `document`. IAM 이 사용자 가입/역할 변경 때, 그리고 **기동 시 전체 재동기화**(영구 탈퇴 `DELETED` 계정은 건너뜀)로 `system:doro#admin|super_admin` 과 `user:<id>#manager|super_manager` 튜플을 쓴다. 영구 탈퇴 시에는 그 사용자의 IAM 튜플을 지운다. [코드: `UserRelationSyncService`, `AccountPurgeService`]

### 6.6 역할과 인가의 이중 구조
- **JWT `role` 클레임**(`USER/ADMIN/SUPER_ADMIN`)은 IAM DB 의 `users.role` 에서 나오고, IAM 의 `hasAnyRole` 같은 정적 검사에 쓰인다.
- **Guard 튜플**은 IAM 이 역할 변경 시 동기화한다. IAM 의 관리자 API 는 JWT 역할로 1차 거르고 **Guard 로 최종 판정**한다. 역할이 바뀌면 대상의 세션을 모두 종료해 낡은 클레임을 없앤다.
- 워크스페이스 룰은 "역할 분기문 금지, Guard 에 위임"이다. **서브서비스의 관리자 판정은 `guardClient.check("system","doro","admin",userId)` 로 하고 `DoroUser.isAdmin()` 에 의존하지 말 것.** 클레임은 최대 토큰 수명(기본 15분, 폐기 확인을 켜지 않으면 강등 후에도) 낡을 수 있다.
- IAM 의 Guard 판정(`check`)은 실패 시 **fail-closed**(거부)다. 튜플 동기화는 경로마다 다르다(`UserRelationSyncService`): **역할 변경**은 `syncUserTuplesOrThrow` — Guard 실패를 삼키지 않고 503 `GUARD_UNAVAILABLE` 로 알리며 호출 트랜잭션이 DB 역할 변경을 되돌린다(강등했는데 관리자 튜플이 남는 상태를 막는다). 순서는 "잃는 권한의 튜플만 먼저 삭제 → 새 튜플 쓰기"라서 중간 실패에도 Guard 는 DB 보다 권한이 **적은 쪽**으로만 어긋난다. **가입·탈퇴 유예 복구**는 `syncUserTuplesBestEffort` — 계정 작업은 이미 끝났으므로 실패해도 예외 없이 ERROR 로그(사용자 ID 만)만 남기고 기동 시 전체 동기화가 복구한다. **영구 탈퇴**는 튜플 삭제가 실패하면 익명화하지 않고 다음 주기에 재시도한다.
- 서비스 쪽 튜플도 같은 문제(DB 와 Guard 가 한 트랜잭션이 아님)가 있다. 참조: doro-party 는 **튜플 삭제 아웃박스**를 쓴다. [코드·테스트: doro-party `infra/guard/GuardTuples`, `GuardDeleteOutbox`, `GuardDeleteOutboxRetrier`, `GuardTupleDeleteOutbox`, `GuardDeleteOutboxTest`, 마이그레이션 `V12__guard_tuple_delete_outbox.sql`]
  - **`GuardTuples.deleteAfterCommit`**: 트랜잭션이 있으면 지울 튜플을 **같은 도메인 트랜잭션에서** `guard_tuple_delete_outbox` 에 한 행으로 넣고(롤백되면 행도 사라지고 Guard 삭제 시도도 없다), `afterCommit` 에서 즉시 `deleteTupleOrThrow` 를 시도한다. 성공하면 행을 지우고(새 트랜잭션), 실패하면 예외 없이 행에 실패를 기록한다(요청은 이미 커밋돼 성공). 트랜잭션이 없으면 바로 지우고 **실패했을 때만** 행을 남긴다. 쓰기 중 롤백돼 남은 고아 튜플의 정리도 같은 방식으로 실패하면 큐에 남긴다. 탈퇴 정리처럼 "못 지우면 전체를 실패시켜 다시 하려는" 경로는 `deleteOrThrow`(아웃박스 없이 예외 전파)를 쓴다.
  - **`GuardDeleteOutboxRetrier`**: `@Scheduled`, 기본 30초 간격(`party.guard-outbox.interval`, 첫 실행도 같은 지연), 한 번에 `batch-size`(기본 100)개, 시도 시각이 된 행을 오래된 순으로 처리. 행마다 새 트랜잭션에서 행을 **잠그고**(다른 인스턴스가 잡은 행은 건너뜀) 삭제를 시도하며, 성공하면 행을 지우고 실패하면 `attempts` 를 올리고 다음 시도를 `10초 × 2^(실패 횟수-1)`(상한 `max-backoff`, 기본 1시간)로 미룬다. 새 행의 첫 `next_attempt_at` 은 생성 시각+10초라 커밋 직후의 즉시 시도가 먼저 끝난다. **`attempts` 가 10이 되는 순간 ERROR 로그를 한 번** 남긴다(`stale permission remains … inspect guard_tuple_delete_outbox`). 10회 뒤에도 행은 지워지지 않고 상한 간격으로 계속 재시도한다. `party.guard-outbox.enabled=false` 면 재시도기는 아무것도 하지 않는다.
  - 이미 없는 튜플을 지우는 것은 오류가 아니라서(테스트 `deletingAnAbsentTupleIsFine`) 중복 시도가 안전하다. 테스트는 Guard 가 죽어 있어도 요청이 성공하고 복구 뒤 재시도로 정리됨, 롤백 시 행 없음, 백오프 증가·상한, 배치 크기·오래된 순, 공유 끊기(권한 회수)가 Guard 장애 뒤 반영됨을 고정한다. 여러 서비스에 같은 패턴이 있는지는 doro-party 외에는 읽지 않았다.
- Guard 튜플 정합성 점검 도구: `scripts/guard-reconcile.sh`(`--report` 읽기 전용이 기본, `--fix-orphans` 는 ① IAM 에서 삭제됐거나 없는 사용자가 주체인 튜플 ② 서비스 DB 에서 사라진 객체의 튜플만 삭제하고 소유자 튜플 누락·역할 불일치는 보고만 한다). 서버의 DB 를 직접 읽는 운영 스크립트라 서비스 코드에서 부르지 않는다. [코드: `scripts/guard-reconcile.sh` 본문, `scripts/test-guard-reconcile.sh`(CI)]
  - 방식: 슈퍼유저로 IAM(`doro_auth.users`: id·role·status), 블로그(`service_blog`)·파티(`service_party`)의 객체 ID 와 소유 관계 컬럼만 `COPY` 로 `doro_guard` 세션의 임시 테이블에 옮기고 `relation_tuples` 와 SQL 로 대조한다(이메일·닉네임 등은 읽지 않음, 보고에는 ID 만). **게임(`service_games`)은 대조 대상이 아니다**. 점검 대상이 아닌 네임스페이스는 개수만 보고한다.
  - 분류: 1 주체가 `DELETED`/없는 IAM 사용자 ·2 객체(또는 그룹 등 비사용자 주체)가 서비스 DB 에서 사라짐 → **삭제 대상**. 3 객체는 있는데 소유자·작성자·멤버십·공유 튜플 없음 ·4a `system` 네임스페이스에 IAM 역할로 설명되지 않는 튜플(권한 과다, 크게 경고) ·4b 역할은 있는데 `system:doro` 튜플 없음 ·4c `user` 객체의 `manager`/`super_manager` 불일치 ·5a 스토리지(MinIO) 에는 있는데 DB 가 안 쓰는 파일 ·5b DB(`pin_photos`)에는 있는데 스토리지에 없는 행 → **보고만**(어떤 모드에서도 지우지 않음).
  - 안전장치: 생성 10분(`--grace-minutes`) 이내 튜플은 1·2 에서 제외(서비스가 DB 커밋 전에 튜플을 먼저 쓰므로), 삭제 대상이 `--max-delete`(수동 기본 2000, `--auto` 500)를 넘으면 **아무것도 지우지 않고 중단**, IAM `users` 가 비어 있거나 조회 DB 하나라도 읽지 못하면 중단, 삭제 전 `doro_guard` `pg_dump` 백업(최근 14개 유지), 삭제는 한 트랜잭션, 멱등. 5a 는 수정 시각이 60분(`--storage-grace-minutes`) 이내면 제외, MinIO 를 못 읽으면 분류 5 만 건너뜀.
  - `--auto`(cron, `install-guard-reconcile-cron.sh` 가 사용자 crontab 에 매일 04:30 한 블록, `flock` 중복 방지)는 `--fix-orphans` 와 같되 화면 출력 없이 한 줄 요약을 `~/backups/auto/guard-reconcile.log`·syslog 에 남기고, `ALERT`(4a 있음, 종료 코드 3)·`ABORT` 는 알림 스크립트가 있으면 개수만 담아 보낸다. 종료 코드: 0 어긋남 없음, 1 남음, 2 사용법 오류. 실제 서버에서 마지막으로 돌린 결과·cron 설치 여부는 `[미검증]` — 확인: `ssh mini 'bash -s -- --check' < scripts/install-guard-reconcile-cron.sh`(읽기 전용: 설치 상태·다음 실행·마지막 로그), 읽기 전용 점검 `ssh mini 'bash -s -- --report' < scripts/guard-reconcile.sh`.

---

## 7. 응답·에러·로깅 규약 (참조 구현 doro-blog 기준)

- 서브서비스 `ApiResponse<T>` 성공/실패 봉투를 **자체 정의**한다(IAM 과 별개). 실패는 `GlobalExceptionHandler` 로 통일.
- 반드시 매핑할 예외: `HttpRequestMethodNotSupportedException → 405`, `HttpMessageNotReadableException → 400`. `DoroAccessDeniedException → 401/403` 은 SDK 기본 어드바이스(§4.6)가 처리하지만 서비스 봉투 형식에 맞추려면 자체 핸들러를 둔다(있으면 그쪽이 우선). [코드: `doro-blog/AGENTS.md`, `GlobalExceptionHandler`]
- 로그는 **stdout + SLF4J**. `X-Trace-Id` 는 SDK 필터가 MDC 에 넣고 Guard 호출에도 전파된다. 서비스 간 HTTP 호출에는 직접 헤더로 전파. 토큰·세션 ID·TOTP 시크릿을 로그에 남기지 말 것.
- DB: **Flyway + `ddl-auto: validate`**. `hibernate.ddl-auto: update` 금지. Flyway 히스토리 테이블은 `{name}_schema_history`. [코드: 워크스페이스/Doro AGENTS.md, auth·guard `application.yaml`]

---

## 8. 레시피

### A. 새 서브 서비스(REST 백엔드) 추가 — 체크리스트
`Doro/AGENTS.md` §5 의 5단계를 **아래 보정**과 함께 수행한다.

1. **DB**: `service_{name}` 을 `docker-compose.yml` 의 `POSTGRES_MULTIPLE_DATABASES`, `.env.example`, `.env` 에 추가. Flyway `{name}_schema_history`, `ddl-auto: validate`. 서비스 전용 DB 계정으로 접속하게 하려면 `scripts/split-db-accounts.sh` 의 서비스 목록에 추가해야 한다(현재 blog party games auth guard bff 만 지원, §1).
2. **SDK**: Java **25**, `includeBuild('../Doro')` + `dependencySubstitution`, `-parameters` 컴파일 옵션, `doro.iam.jwks-uri`/`doro.guard.*` 설정. **Spring Security 스타터는 넣지 않는다.** Guard 가 `ENFORCE` 이면 서비스 전용 토큰(`doro.guard.service-token`)을 발급받아 넣는다(운영자에게 요청 — 토큰 값은 코드/로그/문서에 남기지 않음). 선택: `revocation-check`(로그아웃 즉시 반영), `issuer-validation`/`audience`.
3. **스키마**: 서비스 접두사 네임스페이스로 `.doro` 작성 → **§6.3 절차(GET → 병합 → POST)** 로 등록. **절대 자기 타입만 POST 하지 말 것.** 등록 직전에 기존 DSL 을 백업(`GET` 결과를 파일로 저장)해 두면 복구가 쉽다. `schema-write` 권한이 필요하다.
4. **컨트롤러**: 조회는 공개 가능. 변경은 `@DoroGuard` 또는 `@CurrentDoroUser` + `isAuthenticated()` 확인. **`@CurrentDoroUser UUID` 는 비로그인 시 null.** SDK 기본 예외 어드바이스가 401/403/503 을 처리한다(자체 핸들러로 봉투 형식을 맞출 수 있음).
5. **튜플 동기화**: 리소스 생성 시 `writeTuple`, 삭제 시 `deleteTuple` — 가능하면 **`writeTupleOrThrow`/`deleteTupleOrThrow`** 를 쓰거나 반환값 0 을 실패로 취급(로그+재시도/보상). 리소스 저장 후 튜플 쓰기 실패 시 고아 상태가 될 수 있음을 설계에 반영. **삭제는 유실되면 권한이 남으므로 재시도 수단(아웃박스 등, §6.6 의 doro-party 사례)을 둔다.** Guard 를 호출자별 네임스페이스로 제한한 환경(§6.1)에서는 서비스 토큰이 소유한 네임스페이스(예: `blog_*`) 밖의 튜플을 못 쓰므로 **서비스 접두사 네임스페이스를 지킬 것**.
6. **게이트웨이**: `Doro/gateway/nginx.conf` 에 `upstream` + `location` 추가(라우팅 우선순위 주의 — `docs/GATEWAY_ROUTING_RULES.md`). **Guard 를 프록시하지 말 것.** 게이트웨이는 CI 로 배포되지 않는다(서버에 직접 반영 + `nginx -t` + reload). 라우팅 시험 기대 목록 `scripts/gateway-routing/expect-current.txt` 도 함께 고친다(CI 가 `scripts/test-gateway-routing.sh` 로 검사). 서비스 경로는 `/<서비스>/`(웹)과 `/<서비스>/api/`(API) 형태가 현재 관례(`/blog/`, `/party/`, `/games/`).
7. **브라우저 로그인이 필요하면(도로로 로그인)**: 서비스에 로그인 폼·세션을 만들지 말고 BFF 를 쓴다. ① 관리자가 OAuth 클라이언트를 `firstParty: true` 로 등록, `redirect_uri` = `<공개 origin>/<서비스>/api/v1/bff/callback` ② SDK 의 `doro.iam.oauth-client-ids` 에 그 클라이언트 ID ③ 서비스는 Bearer 검증 + `GET /api/v1/bff/session` 만(gateway 모드), 또는 SDK BFF 모듈 빈 등록(embedded 모드, §4.7) ④ gateway 모드면 `doro.bff.services.<이름>` 설정 + 게이트웨이의 로그인·콜백·로그아웃 location, `/_bff_auth_<서비스>`, API location 의 `auth_request`([BFF.md](BFF.md) "새 서비스를 붙이는 순서"). 영구 탈퇴 정리가 필요하면 `DeletedUserHandler` 구현(§4.7)과 IAM 내부 토큰 등록.
8. **검증**: 빌드, 컨테이너 기동 `healthy`, 실제 엔드포인트 호출(워크스페이스 룰 §5). §11 명령으로 인증/인가를 **직접 확인**.

### B. 서버 렌더링(Thymeleaf 등) 웹앱이 Doro 로 로그인하기

> 게이트웨이 뒤의 **자사 서비스**(블로그·파티·게임 같은 SPA + API)는 아래 방법을 직접 구현하지 말고 §1.1-2 의 BFF(SDK 모듈 또는 `doro-bff`)를 쓴다. 아래는 BFF 가 맡지 않는 별도 서버 렌더링 앱 기준이다.

**방법 1 — OAuth 2.1/OIDC 인가 코드 + PKCE (권장, 비밀번호를 직접 다루지 않음)**
1. 관리자가 클라이언트를 등록한다(`POST /api/v1/admin/oauth/clients`, §5.3). 서버 렌더링 앱의 `redirect_uri`(https)를 정확히 등록한다.
2. 앱이 `code_verifier`(43~128자 unreserved)/`code_challenge`(S256)/`state`/`nonce` 를 만들고 사용자를 `/oauth2/authorize` 로 보낸다(§5.1). 로그인·동의는 포털이 처리한다.
3. 콜백에서 `state` 를 검증하고, **서버에서** `POST /oauth2/token`(form, `grant_type=authorization_code`)으로 교환한다. `id_token` 의 `aud`/`nonce`/서명(JWKS)을 검증한다.
4. 액세스/리프레시 토큰은 **`HttpOnly; Secure; SameSite=Lax` 쿠키 또는 서버 세션**에 둔다(JS 노출 금지). 만료 시 `refresh_token` grant 로 갱신하고 **새 리프레시 토큰으로 교체**(RTR — 이전 토큰은 즉시 무효, 동시 갱신 경합 주의, 재사용 감지 시 세션 종료).
5. 서버 쪽 로그인 상태는 앱 자체 세션/쿠키로 관리하고, 서브서비스 API 호출에는 액세스 토큰을 `Authorization: Bearer` 로 보낸다. 이 토큰은 `role=USER`/`cid` 이므로 **권한 판정은 Guard** 로 한다.
6. 로그아웃: 앱 세션/쿠키 삭제. OAuth 액세스 토큰은 IAM 이 인증으로 인정하지 않으므로(§3.1) `POST /api/v1/auth/logout` 에 **쓸 수 없다**. OAuth 세션도 사용자의 활성 세션 목록(`GET /api/v1/sessions`)에 보이므로, 사용자가 **일반 로그인 토큰**(포털)으로 `DELETE /api/v1/sessions/{id}` 로 종료할 수 있다. 클라이언트 쪽에서 OAuth 세션을 종료하는 전용 API 는 없다. 포털 `MyAccountPage` 의 '기기 및 세션 킬스위치' 탭은 `GET /api/v1/sessions` 응답을 **필터 없이** 그대로 나열하고(`web/src/pages/MyAccountPage.tsx`) 그 API 도 OAuth 세션을 걸러내지 않으므로(`SessionService.getActiveSessions`), OAuth 세션은 `deviceInfo` = `OAuth2 Client: <client_id>`, IP = `OAuth2` 로 표시되고 현재 세션이 아니면 종료 버튼이 보인다. 종료 API(`DELETE /api/v1/sessions/{id}`)는 소유자만 확인하고 세션 종류는 가리지 않는다(`SessionRevocationService.revokeOwnSession`). [코드. 실제 화면 렌더링은 브라우저로 확인하지 않았다 — `[미검증]`: 포털(`/account` → 세션 탭)에서 서비스에 로그인한 뒤 항목이 보이는지 눈으로 확인]
7. 쿠키 인증이므로 상태 변경 요청에는 CSRF 방어가 필요하다(`SameSite`+Origin 검사 또는 토큰).
8. 앱이 서브서비스 SDK 도 쓴다면: 쿠키를 SDK 가 직접 읽게 `doro.iam.cookie-name` 을 설정하는 방법이 있다(헤더가 없을 때만 쿠키 사용). 쿠키에 **액세스 토큰**이 들어 있어야 한다.

**방법 2 — 인앱 로그인 + HttpOnly 쿠키 (같은 origin, 앱이 로그인 폼을 직접 렌더링)**
1. 서버가 **서버 간 호출**로 `POST {IAM}/api/v1/auth/login` 을 한다. 응답 `data.requires2fa` 가 true 면 `tempTicket` 을 세션에 잠깐 두고 OTP 폼 → `POST /api/v1/auth/2fa/login`.
2. 받은 토큰을 `HttpOnly; Secure; SameSite=Lax` 쿠키로 내려주고, SDK 의 `doro.iam.cookie-name` 으로 쿠키를 읽게 한다(이전처럼 필터 래핑이 필요 없다). 만료 시 `POST /api/v1/auth/token/refresh` 로 갱신하고 쿠키 교체.
3. 로그아웃: `POST /api/v1/auth/logout`(Bearer = 현재 액세스 토큰) 후 쿠키 삭제. 서비스에서 폐기 확인을 켜면 즉시 반영된다.
4. 한계: 웹앱이 **비밀번호를 직접 다루게 된다.** 가능하면 방법 1 을 쓴다.

### C. 인가 설계 원칙
- **본인 데이터**(내 구독, 내 알림)는 서비스 DB 의 `user_id = :currentUser` 조건으로 충분. 굳이 튜플로 만들지 않는다.
- **관리자 기능**은 `guardClient.check("system","doro","admin", userId)`. 클레임(`role`)에 의존하지 않는다.
- **공유/계층 리소스**(글, 시리즈, 채널)는 튜플 + `@DoroGuard`.
- 튜플 키는 **문자열 ID** (UUID `.toString()`). 사용자 subject 는 `subjectNamespace="user"`, `subjectId=<UUID>`.

### D. 포털과 서브서비스의 토큰 공유 주의
포털은 계정마다 리프레시 토큰을 슬롯별 HttpOnly 쿠키(`doro_rt_0`~`doro_rt_4`, 최대 5계정)에 두고 액세스 토큰은 탭 메모리에만 둔다. 새로고침하면 메모리 토큰이 비므로 첫 요청이 쿠키로 갱신(`refreshAccessToken`)한 뒤 보낸다. 이전 방식(localStorage 에 저장된 리프레시 토큰)으로 로그인돼 있던 계정은 첫 갱신 때 그 토큰을 한 번 본문으로 보내 쿠키로 옮기고 저장소에서 지운다. 같은 쿠키를 여러 탭이 동시에 회전시키지 않도록 갱신은 Web Locks 로 직렬화하고, 실패는 `refreshed / rejected / unavailable` 로 구분해 **서버가 거부(400/401/403/404)한 경우에만 로그아웃**하며 네트워크 오류·5xx 는 로그인 상태를 유지한다. 참고: `Doro/web/src/api/tokenRefresh.ts`, 서버 `RefreshTokenCookies`. 같은 도메인의 다른 서비스에서 XSS 가 나도 리프레시 토큰은 읽을 수 없지만, 같은 origin 의 스크립트가 쿠키가 실린 갱신 요청을 보내 액세스 토큰을 새로 받는 것까지는 막지 못한다(브라우저 모델의 한계). 포털 "모든 계정 로그아웃"(`web/src/api/logoutAll.ts`)은 계정별로 **자신의 액세스 토큰(필요하면 쿠키로 갱신 후)으로** `POST /api/v1/auth/logout?sessionId=` 를 쿠키 방식 헤더와 함께 호출해 서버가 쿠키도 지우게 하고, 실패한 계정은 목록으로 사용자에게 알린다(로컬 정리는 계속).

---

## 9. 보안 구성 가이드 (켤 수 있는 기능과 권장 설정)

Doro 의 보안 기능은 **단계적으로 켤 수 있게** 설계돼 있다. 기본값은 기존 서비스가 깨지지 않도록 호환 쪽이고, 운영 환경에서는 아래를 권장한다. 모든 항목은 환경 변수/속성으로 제어하며 되돌리기도 같은 방식이다.

| 기능 | 켜는 방법 | 효과 |
|---|---|---|
| **Guard 서비스 인증** | `DORO_GUARD_SECURITY_MODE=WARN` 으로 호출자를 확인한 뒤 `ENFORCE`. 호출자별 토큰 `DORO_GUARD_SERVICE_TOKENS=auth:<토큰>,blog:<토큰>:schema-write` (`scripts/split-guard-tokens.sh` 가 전환·검증·자동 복구) | 서비스가 아닌 호출 차단, 호출자 식별, 스키마 교체는 `schema-write` 권한이 있는 호출자만 |
| **내부 API 호출자 인증**(`/internal/**`) | `DORO_IAM_INTERNAL_TOKENS=blog:<토큰>[,호출자:<토큰>…]`(토큰 32자 이상 무작위, 형식 오류·짧은 토큰·이름 중복이면 **기동 실패**), 호출자는 헤더 `X-Doro-Service-Token` 으로 보낸다. 모드 `DORO_IAM_INTERNAL_AUTH_MODE`: **ENFORCE(기본)**, 도입 중에는 `WARN`(실패를 경고만 하고 통과), `OFF`. 게이트웨이를 거친 요청(`X-Forwarded-For`/`X-Real-IP`)은 토큰이 맞아도 **404** | 네트워크 격리에만 기대지 않고 호출자를 확인, 토큰 없는 요청은 401(표준 오류 본문), 토큰 값은 로그에 남지 않음 |
| **Guard 튜플/스키마 검증** | `DORO_GUARD_VALIDATION_MODE=WARN` → 로그 확인 → `ENFORCE` | 스키마에 없는 타입/릴레이션 튜플, 오타·미선언 타입 스키마 거부 |
| **JWT 개인키 암호화** | `DORO_IAM_JWT_KEY_ENCRYPTION_SECRET` (AES-256-GCM, 기존 키 자동 이전). 키 회전은 `previous-key-id` + `previous-public-key-pem` | Redis 에 보관하는 서명 키를 저장 시 암호화, 무중단 키 교체 |
| **2FA(TOTP) 시크릿 암호화** | `DORO_IAM_TOTP_ENCRYPTION_KEY`(32바이트 base64, `openssl rand -base64 32`). 비우면 평문 저장 + 기동 시 경고. 교체는 이전 키를 `DORO_IAM_TOTP_PREVIOUS_KEYS` 로. 운영 적용 `scripts/enable-totp-encryption.sh` | DB 덤프·백업이 새도 2FA 시크릿이 드러나지 않음. 저장 형식 `enc:v1:<base64(iv+암호문+태그)>`(AES-256-GCM, 컬럼 용도를 AAD 로 묶어 활성↔대기 컬럼 간 옮겨 붙이기 불가). 예전 평문 값도 읽고 키가 있으면 기동 시 암호문으로 이전한다. **키를 잃으면 암호화된 2FA 를 못 쓴다(복구: 관리자의 2FA 초기화)** |
| **세션 폐기 즉시 반영** | SDK `doro.iam.revocation-check: WARN` → `ENFORCE` | 로그아웃/세션 종료가 서브 서비스에 즉시 반영 (캐시·백오프·fail-open 설정 제공) |
| **OAuth 클라이언트 등록 강제** | `DORO_OAUTH_CLIENT_REGISTRY_MODE=ENFORCE` | 등록된 `client_id` 와 `redirect_uri` 정확 일치만 허용 |
| **audience / issuer 검증** | SDK `doro.iam.audience`, `doro.iam.issuer-validation: ENFORCE` | 토큰 대상·발급자 확인 |
| **OIDC 공개 URL** | `DORO_IAM_ISSUER` 를 공개 URL 로 | 외부 OIDC 클라이언트의 discovery 가 올바른 주소를 가리킴 |
| **세션/요청 제한** | `DORO_IAM_SESSION_MAX_ACTIVE_PER_USER`, `DORO_IAM_SESSION_MAX_OAUTH_PER_CLIENT`(기본 3), `DORO_IAM_SESSION_ABSOLUTE_LIFETIME_SECONDS`, `DORO_IAM_RATE_LIMIT_*` | 사용자당 활성 세션 상한, 서비스(OAuth 클라이언트)당 기기 수, IP 단위 요청 제한 |
| **탈퇴 유예·토큰 정리** | `DORO_IAM_ACCOUNT_DELETION_GRACE_DAYS`(30), `DORO_IAM_ACCOUNT_DELETION_PURGE_INTERVAL_MS`(3600000), `DORO_IAM_TOKEN_PURGE_ENABLED`(true), `DORO_IAM_TOKEN_PURGE_RETENTION_DAYS`(90, 리프레시 토큰 유효기간 30일보다 길게) | 탈퇴 유예 후 익명화, 만료·폐기 토큰과 비활성 세션의 주기 삭제 |
| **BFF**(브라우저 로그인을 서비스 밖에서) | `BFF_SESSION_KEY`(필수), `BFF_IAM_INTERNAL_TOKEN`, `BFF_PUBLIC_ORIGIN`, `BFF_DB`, `BFF_PORT` — [`BFF.md`](BFF.md). 서비스별 전환은 서비스의 `BLOG_AUTH_MODE`/`PARTY_AUTH_MODE`/`GAMES_AUTH_MODE`(`embedded`\|`gateway`), 보조 스크립트 `scripts/enable-bff.sh`, `scripts/set-bff-mode.sh` | 쿠키 세션을 게이트웨이가 Bearer 로 바꿔 서비스에 전달 |
| **Guard 호출자 네임스페이스** | `DORO_GUARD_SERVICE_TOKENS=blog:<토큰>:schema-write:blog_*`(§6.1), 보조 스크립트 `scripts/set-guard-namespaces.sh`(§13) | 서비스 토큰이 다른 서비스·IAM 의 권한 데이터를 바꾸지 못하게 |
| **서비스별 DB 계정** | `AUTH_DB_USER`/`GUARD_DB_USER`/`BFF_DB_USER` 와 `*_DB_PASSWORD`, `scripts/split-db-accounts.sh` (§1) | 한 서비스의 DB 접속 정보로 다른 서비스 DB 를 읽지 못하게 |
| **첫 관리자 부트스트랩** | `DORO_IAM_BOOTSTRAP_TOKEN`(32자↑, 기본 꺼짐, 쓴 뒤 지움), `scripts/bootstrap-first-admin.sh` (§2.2) | 최고 관리자가 없을 때 한 번만 본인을 SUPER_ADMIN 으로 승격 |

연동 코드를 쓸 때의 동작 방식(필터는 요청을 막지 않는다, `@CurrentDoroUser UUID` 는 비로그인 시 `null`, `writeTuple` 은 실패 시 `0`, `@DoroGuard` 의 Guard 장애는 503·서비스 토큰 오설정은 500 으로 구분 등)은 §4·§6·§10 에 있다. 이 가이드와 코드가 다르면 **코드가 맞다.**

### 📋 과거 문서에서 자주 틀리던 항목 (참고)
| 흔한 오해 | 실제 |
|---|---|
| 가입 필드 `fullName` | `name` |
| 로그인 응답 `status: SUCCESS`, `twoFactorTicket` | `{requires2fa, tempTicket, tokens}` |
| 2FA 설정 응답 `secretKey`, `otpAuthUri` / `/2fa/enable`, `/login/2fa`, `totpCode` | `secret`, `qrUri` / `/2fa/verify`, `/2fa/login`, `code` |
| 계정 잠금 423 | 403 |
| `/accounts/add`, `/accounts/switch/{uidx}` | 존재하지 않음(포털 프론트엔드 기능) |
| Guard `/api/v1/tuples`, `/tuples/check`, `/schemas` | `/api/v1/guard/tuples`, `/guard/check`, `/guard/schema` |
| 스키마 등록 `text/plain`, 자기 타입만 | JSON `{dsl}`, **전체 교체** |
| DSL 괄호 `(a & b) - c` | 미지원(깨짐) |
| 액세스 토큰 24시간 | 기본 15분(과거 코드는 24시간이었음) |
| L1/L2 캐시 | L1 만 (Guard 는 Redis 미사용) |

---

## 10. 하지 말아야 할 것 (에이전트 체크리스트)

- ❌ USER_GUIDE/README 의 엔드포인트·필드를 그대로 복사해 코드를 쓰지 말 것 — 이 문서와 코드로 대조.
- ❌ `POST /api/v1/guard/schema` 에 자기 타입만 보내지 말 것.
- ❌ `/internal/**` 을 게이트웨이에 라우팅하지 말 것(내부 API 는 Docker 내부 네트워크 전용이고 호출자 토큰으로 한 번 더 지킨다). 내부 API 토큰 값을 코드·로그·문서·채팅에 남기지 말 것.
- ❌ Guard 포트(8081/9090)를 게이트웨이나 외부에 노출하지 말 것. Guard 서비스 토큰 값을 코드·로그·문서·채팅에 남기지 말 것.
- ❌ `DoroUser.role` 이나 클레임으로 관리자 권한을 판정하지 말 것(Guard 에 위임).
- ❌ `@CurrentDoroUser UUID` 를 null 검사 없이 쓰지 말 것.
- ❌ `writeTuple` 반환값을 무시하지 말 것(또는 `writeTupleOrThrow` 사용). 튜플 **삭제** 실패를 삼키지 말 것(권한이 남는다 — 재시도/아웃박스).
- ❌ Guard 장애(503 `GUARD_UNAVAILABLE`)·설정 오류(500 `GUARD_MISCONFIGURED`)를 "권한 없음 403"으로 뭉개거나 반대로 403 을 장애로 재시도하지 말 것(§4.6).
- ❌ 서비스에 로그인 폼·세션·리프레시 토큰 보관 코드를 새로 만들지 말 것 — BFF(§1.1-2). 특히 gateway 모드 서비스는 쿠키로 인증하는 코드를 두지 않는다(Bearer 만).
- ❌ `/internal/v1/**` 응답을 사용자에게 그대로 노출하거나 게이트웨이로 라우팅하지 말 것(§2.6).
- ❌ OAuth 액세스 토큰/`id_token` 으로 IAM 관리자 API 를 호출하거나 `id_token` 을 API 인증 토큰으로 쓰지 말 것.
- ❌ 401/403/CORS 가 나온다고 `permitAll()`/`*` 로 개방하지 말 것(워크스페이스 룰). 원인을 찾을 것(CORS 는 허용 Origin 패턴 목록에 추가).
- ❌ 토큰·세션 ID·시크릿·`.env`·`server-secret` 내용을 로그·응답·문서·채팅에 남기지 말 것.
- ❌ Doro 저장소를 수정하는 작업(클라이언트 등록 코드, 킬스위치 연동 등)을 사용자 승인 없이 하지 말 것.
- ❌ 이 문서를 근거로 "동작한다"고 사용자에게 보고하지 말 것. 워크스페이스 룰 §5: **직접 빌드하고 실행해 확인**한 뒤에만 보고.

---

## 11. 빠른 검증 명령 (전제 재확인용)

IAM/Guard 가 **로컬**에서 떠 있다고 가정(`docker compose up -d` in `Doro/`; compose 가 8080/8081 을 게시한다. 컨테이너 없이 IAM 을 직접 띄웠다면 `DORO_GUARD_URL` 확인).

```bash
# 1) 토큰 TTL 과 실제 응답 형태 확인 (가입 → 로그인). 테스트 계정은 직접 만든 값 사용.
curl -s -X POST localhost:8080/api/v1/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"probe@example.test","password":"ProbePass123!","name":"probe"}'
curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"probe@example.test","password":"ProbePass123!"}'   # data.tokens.expiresIn == 900 ? (환경변수로 바꿨다면 그 값)

# 2) JWKS (현재 키 + 로테이션 중이면 이전 키)
curl -s localhost:8080/.well-known/jwks.json

# 3) 세션 확인 / 로그아웃 (위 로그인 응답의 accessToken 사용, 값은 셸 변수로만 다루고 출력하지 말 것)
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/sessions/current -H "Authorization: Bearer $ACCESS"   # 204
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/auth/logout -H "Authorization: Bearer $ACCESS" # 200
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/sessions/current -H "Authorization: Bearer $ACCESS"   # 401 (킬스위치/DB 즉시 반영)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/auth/logout                                  # 401 (Bearer 없음)

# 4) Guard 활성 스키마 (변경 전 항상 백업)
curl -s localhost:8081/api/v1/guard/schema > schema.backup.json

# 5) Guard 서비스 인증 모드 확인 (서버가 로컬 compose 면 기본 OFF → 200, ENFORCE 면 토큰 없이 401)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8081/api/v1/guard/check \
  -H 'Content-Type: application/json' \
  -d '{"namespace":"system","objectId":"doro","relation":"admin","subjectNamespace":"user","subjectId":"00000000-0000-0000-0000-000000000000"}'

# 6) 폐기 확인 동작 재현: 서브서비스에 doro.iam.revocation-check=ENFORCE 를 켠 상태에서
#    로그인 → 토큰 저장 → logout → 같은 토큰으로 서브서비스 호출이 익명 처리(보호 API 401/403)되는지.
#    (OFF 이면 만료 전까지 계속 통과하는 것이 정상)

# 7) OAuth: 미등록 client/redirect 거부 확인 (등록 없이 + 허용 목록 비어 있음 → 400; 기본 ENFORCE 이며 WARN 도 허용 목록이 비면 거부)
curl -s -o /dev/null -w '%{http_code}\n' \
  'localhost:8080/oauth2/authorize?response_type=code&client_id=probe&redirect_uri=https://example.test/cb&code_challenge=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&code_challenge_method=S256'

# 8) 내부 API 호출자 인증: 토큰 없이 직접(게이트웨이 거치지 않고) 부르면 ENFORCE(기본)에서 401, 게이트웨이를 거친 요청이면 404
curl -s -o /dev/null -w '%{http_code}\n' 'localhost:8080/internal/v1/deleted-users'                                   # 401 (코드상 예상, 실행 안 함)

# 9) (BFF 를 띄운 로컬 compose 에서) BFF 헬스 — 호스트 포트는 127.0.0.1:8085
curl -s localhost:8085/actuator/health                                                                                # {"status":"UP"} 예상 (실행 안 함)
```

- 테스트 데이터는 반드시 **로컬/테스트 환경**에서만. 운영 서버에 호출하지 말 것.
- 이 문서의 `[미검증]` 항목은 위와 같은 실험이나 테스트로 확인한 뒤, 결과를 이 문서에 반영한다.

---

## 12. 소스 위치 색인 (Doro 저장소 기준)

| 주제 | 파일 |
|---|---|
| 로그인/2FA/토큰 | `auth/.../domain/auth/service/AuthService.java` |
| 토큰 발급·JWKS·키 | `auth/.../core/token/JwtTokenProvider.java`, `JwtKeyProvider.java`, `JwtAuthenticationFilter.java` |
| 리프레시 회전 | `auth/.../core/token/RefreshTokenService.java` |
| 세션 | `auth/.../domain/session/service/{SessionService,SessionRevocationService}.java`, `core/redis/KillSwitch*.java` |
| 보안 설정·CORS·요청 제한 | `auth/.../config/{SecurityConfig,CorsConfig,AuthRateLimitFilter}.java`, `common/web/ClientIpResolver.java` |
| OAuth/OIDC | `auth/.../domain/oauth/**`(`OAuth2Service`, `OAuthClientRegistry`, `RedirectUriValidator`, `store/*`), `interfaces/api/{OAuth2Controller,AdminOAuthClientController}.java`, 마이그레이션 `V6__oauth_clients.sql` |
| IAM→Guard 동기화 | `auth/.../domain/user/service/UserRelationSyncService.java`, `infrastructure/guard/GuardClient.java` |
| 계정 탈퇴·정지·부트스트랩 | `auth/.../domain/user/service/{AccountDeletionService,AccountPurgeService,AccountSuspensionService,BootstrapService}.java`, `interfaces/api/{AdminController,InternalUserController}.java` |
| 내부 API 인증 | `auth/.../core/internal/{InternalApiAuthFilter,InternalCallerTokens,InternalAuthMode}.java` |
| 로그인 잠금·비밀번호 해싱 | `auth/.../domain/credential/service/{LoginAttemptTracker,CredentialService}.java`, `core/crypto/CustomArgon2PasswordEncoder.java`, `common/web/IpPrefix.java` |
| 리프레시 쿠키 | `auth/.../common/web/RefreshTokenCookies.java` |
| 에러 코드 | `auth/.../common/exception/{ErrorCode,GlobalExceptionHandler}.java` |
| Guard 엔진 | `guard/.../core/engine/{CheckEngine,ExpandEngine}.java` |
| DSL 파서 | `guard/.../core/dsl/parser/DslParser.java` |
| 스키마 등록 | `guard/.../core/dsl/service/SchemaService.java` |
| 튜플·검증 | `guard/.../domain/tuple/service/{TupleService,TupleSchemaValidator}.java`, `common/validation/*` |
| 서비스 인증 | `guard/.../config/{ServiceAuthProperties,ServiceTokenFilter,ServiceTokenServerInterceptor}.java` |
| Guard REST/gRPC | `guard/.../interfaces/api/{Check,Tuple,Schema}Controller.java`, `interfaces/grpc/GuardGrpcService.java` |
| 기본 스키마 | `guard/src/main/resources/schema.doro` |
| gRPC 계약 | `sdk/src/main/proto/doro_guard.proto` (guard 에도 사본 있음) |
| SDK 필터·AOP·클라이언트 | `sdk/.../security/DoroTokenVerifier.java`, `security/filter/DoroJwtAuthFilter.java`, `security/jwks/JwksKeyProvider.java`, `security/revocation/SessionRevocationChecker.java`, `aop/DoroGuardAspect.java`, `client/DoroGuardClient.java`, `web/DoroExceptionHandlerAdvice.java`, `config/{DoroProperties,DoroAutoConfiguration}.java` |
| SDK BFF·탈퇴 동기화 | `sdk/.../bff/**`(`BffAuthService`, `BffController`, `BffSessionAuthFilter`, `BffProperties`), `sdk/.../sync/**`, `config/{DoroBffAutoConfiguration,DoroDeletedUsersAutoConfiguration}.java` |
| doro-bff | `bff/src/main/java/.../bff/{web/AuthRequestController,web/BffGatewayController,config/BffServiceRegistry,store/*}`, `bff/src/main/resources/application.yaml` |
| Guard 호출자 네임스페이스 | `guard/.../config/{CallerNamespaceGuard,ServiceAuthProperties}.java` |
| 포털 | `web/src/api/{client,tokenRefresh,logoutAll}.ts`, `web/src/pages/OAuthConsentPage.tsx`, `web/src/utils/{oauthConsent,consentReturn,afterLogin}.ts` |
| 참조 서비스 | `doro-blog/`(`infra/guard/BlogSchemaInitializer`, `blog-schema.doro`, `GlobalExceptionHandler`, `infra/guard/GuardTuples`), `doro-party/`(`infra/guard/GuardDeleteOutbox*`) |
| 통합(E2E) 시험 | `e2e/`(`run.sh`, `README.md` — 진짜 nginx·BFF·IAM·Guard·서비스·Chromium) |
| 라우팅 규칙 | `Doro/gateway/nginx.conf`, `Doro/docs/GATEWAY_ROUTING_RULES.md` |
| 빌드·배포 | `docker-compose.yml`, `.github/workflows/deploy.yml`, `scripts/*.sh` |
| 엔지니어링 룰 | `workspace/AGENTS.md`, `Doro/AGENTS.md` |

---

## 13. 운영 참고

- **CI/CD** (`.github/workflows/deploy.yml`): `main` 푸시 시 **테스트 → 배포 → 결과 알림** 순서다(PR 에서는 테스트만, GitHub 호스팅 러너에서 돈다). 배포 작업은 CSP 인라인 스크립트 해시 검사·서비스 워커 미리 캐시 점검(배포 전·후)·compose 배포·게이트웨이 reload·헬스 확인·배포 후 스모크 시험을 하고, **배포가 실패하면 이전 이미지로 롤백**한다(`scripts/deploy-rollback.sh`). 테스트는 서로 독립인 세 작업(`test-backend` / `test-web`(웹 + 포털 모바일 레이아웃 회귀 시험 `ci-test.sh mobile`) / `test-scripts`(운영 스크립트·게이트웨이 라우팅·롤백 시험))이 병렬로 돌고(저장소 변수 `TEST_RUNS_ON` 이 호스팅 러너일 때 실제로 동시에 돈다), 그중 `test-backend` 가 `scripts/ci-test.sh backend`(sdk 전체 + H2 에서만 의미 있는 시험 + 실제 PostgreSQL 위의 guard·auth·bff 전체를 한 `gradle:9.5.1-jdk25` 컨테이너에서; 같은 시험을 H2 로 되풀이하지 않는다. 로컬에서 H2 로 전부 돌리려면 `backend-full`)와 `scripts/ci-test.sh web`(`npm ci`, `npm test`, `tsc -b`)을 컨테이너 안에서 실행하고, 통과해야 `deploy` 작업이 돈다. 수동 실행(`workflow_dispatch`)의 `skip_tests` 로 건너뛸 수 있고 배포는 동시에 하나씩만 실행된다.
- **게이트웨이 설정**은 CI 로 갱신되지 않는다: 수동 반영 후 `nginx -t` → reload(상세는 `docs/GATEWAY_ROUTING_RULES.md`). 보안 헤더와 **CSP 는 서버 수준에서 강제**(`Content-Security-Policy`) 상태이며 위반은 `POST /csp-report` 로 수집된다. 단 `/games/` location 은 서버 수준 헤더를 상속하지 않고 자기 헤더를 두며 **`Content-Security-Policy-Report-Only`** 다(`/party/` 는 서비스 전용 CSP 를 강제). [코드: `gateway/nginx.conf`]
- **`gateway/nginx.conf` 전문과 `docs/GATEWAY_ROUTING_RULES.md` 대조(2026-10-20)** [코드]: location·upstream 목록, 우선순위 설명, BFF `auth_request`·`@bff_forbidden`/`@bff_unavailable`·`$bff_authorization` 규칙, `/internal` 미노출은 **일치**한다. 어긋난 곳(라우팅 문서/주석 쪽, 동작에는 영향 없음): 문서 머리의 검증 기준 커밋이 `12d6582` 로 낡음, 문서 §4.1 의 "모든 프록시 location 에 헤더 4개"에서 `/_auth_admin`(Authorization·`X-Real-IP` 만)과 `/_bff_auth_*`(헤더 4개 대신 `X-Doro-Service`·`X-Original-Method`)·`/shield.svg`(`Host` 만)는 예외, 문서에 `/games/` CSP 가 Report-Only 라는 말이 없음, 업로드 한도(`/blog/api/v1/uploads`·`/api/v1/uploads` 16m, `/party/api/` 11m)와 루트 `/api/v1/uploads` location 이 표에 없음, `nginx.conf` 의 `/loki/` 주석은 수집기를 `promtail` 로 적었으나 문서는 Alloy. 실제 서버 사본과의 일치는 `[미검증]` — 확인: 서버에서 `docker exec doro-gateway cat /etc/nginx/nginx.conf | diff - <저장소의 gateway/nginx.conf>`(컨테이너 이름은 `set-bff-mode.sh` 가 쓰는 `doro-gateway`).
- **Guard 서비스 토큰 운영 스크립트**: `scripts/set-guard-mode.sh`(OFF/WARN/ENFORCE 전환, 검증·자동 복구), `scripts/split-guard-tokens.sh`(공유 토큰 → 호출자별 토큰: `--check` → `--apply` → `--finalize`, 되돌리기 `--rollback`, 권한 부여 `--scopes`).
- **자격증명**: compose 는 `POSTGRES_PASSWORD` 가 없으면(그리고 `bff-api` 는 `BFF_SESSION_KEY` 가 없으면) 기동을 거부한다. 교체 절차는 `scripts/rotate-credentials.sh`.
- **운영 스크립트 본문(2026-10-20 에 읽음)** — 모두 **서버(mini)에서 실행**하도록 `ssh mini 'bash -s -- <옵션>' < scripts/<이름>.sh` 형태이고, `--check` 가 읽기 전용이며(옵션 없으면 대부분 `--check`), 비밀 값(토큰·키·비밀번호)은 화면에 출력하지 않는다. `.env` 를 바꾸는 스크립트는 먼저 `.env.bak-<이름>-<시각>` 백업을 만든다. 서버에 실제로 어떤 것이 적용됐는지는 코드로 알 수 없어 `[미검증]` 이고, 각 `--check` 가 확인 방법이다. [코드]
  - `set-guard-namespaces.sh --check|--apply|--rollback`: `.env` 의 `DORO_GUARD_SERVICE_TOKENS`(없으면 중단: 먼저 `split-guard-tokens.sh`)의 각 `이름:토큰[:권한[:네임스페이스]]` 에 소유 네임스페이스(`blog:blog_*,party:party_*,games:games_*`, 환경변수 `NAMESPACES` 로 변경)를 4번째 칸으로 붙인다(이미 4번째 칸이 있거나 목록에 없는 호출자(auth 등)는 그대로 = 제한 없음). `--apply` 는 백업 후 `guard-api` 만 재생성하고 healthy 를 기다린 뒤 **데이터를 쓰지 않는 검증**을 한다: 각 호출자 토큰으로 조회(`/api/v1/guard/check`)가 200 인지, 네임스페이스가 있는 호출자가 소유하지 않은 `system` 네임스페이스 쓰기를 시도하면 **403** 인지(Guard 모드가 `ENFORCE` 일 때만 요구, WARN/OFF 면 통과하므로 위반이 로그만 남는다 — 통과했다면 `system:namespace-probe` 튜플이 생겼을 수 있어 수동 정리 안내), auth/blog 헬스. 어느 단계든 실패하면 `.env` 를 되돌리고 guard 를 다시 만든다(낡은 Guard 는 4번째 칸을 모르고 기동 실패 → 자동 복구). 이미 모두 적용돼 있으면 아무것도 바꾸지 않는다. `--rollback` 은 가장 최근 `.env.bak-namespaces-*` 로 복원한다. 코드 쪽 강제는 `CallerNamespaceGuard`(§6.1: 서비스 토큰 모드를 따라 WARN 은 로그만, ENFORCE 는 403/`PERMISSION_DENIED`) 이고 `+` 로 여러 네임스페이스를 줄 수 있다. 적용 여부 확인: `ssh mini 'bash -s -- --check' < scripts/set-guard-namespaces.sh`(호출자별 "네임스페이스 …/제한 없음"과 Guard 모드 출력).
  - `enable-bff.sh --check|--apply`: `--apply` 는 `.env` 에 없는 것만 채운다 — `BFF_SESSION_KEY`(`openssl rand -base64 32`, 이미 있으면 유지: 바꾸면 모든 BFF 세션 무효), `BFF_IAM_INTERNAL_TOKEN`(`openssl rand -hex 32`)과 같은 값을 `DORO_IAM_INTERNAL_TOKENS` 에 `bff:<토큰>` 으로 추가(기존 항목 유지), `doro_bff` 데이터베이스 생성(있으면 아무 일 없음). **컨테이너는 건드리지 않는다**(다음 배포가 반영). `--check` 는 키·토큰 설정 여부(자릿수만)·`bff:` 항목·공개 주소(`BFF_PUBLIC_ORIGIN`, 없으면 `DORO_IAM_ISSUER`)·DB·`doro-bff-api` 상태를 출력한다.
  - `set-bff-mode.sh <blog|party|games> --check|--gateway|--embedded`: 서비스의 서버 쪽 `~/doro-<서비스>/docker-compose.prod.yml`·`.env` 의 `<SERVICE>_AUTH_MODE` 를 바꾸고(compose 가 변수를 전달하지 않으면 세션 키 줄 앞에 `<VAR>: ${<VAR>:-embedded}` 한 줄을 넣음) **그 서비스 컨테이너만** 재생성한다. `--gateway` 사전 점검(하나라도 틀리면 아무것도 바꾸지 않고 중단): `<SERVICE>_SESSION_KEY` 존재, `doro-bff-api` healthy, 게이트웨이 컨테이너(`doro-gateway`)의 `nginx.conf` 에 `bff/<서비스>/login` 라우트 존재, 실행 중 서비스 이미지의 jar 에 `ConditionalOnEmbeddedBff` 클래스 포함(=모드 스위치가 배포됨). 전환 후 healthy 가 아니면 compose/.env 를 되돌려 재생성. 전환하면 사용자는 한 번 다시 로그인한다. `--embedded` 는 사전 점검 없이 되돌린다. 서비스의 `*_SESSION_KEY` 는 gateway 모드에서도 계속 필요하다고 스크립트가 안내한다.
  - `enable-deleted-sync.sh --check|--apply [--restart-auth]`: **party 와 games 만** 다룬다(blog 는 이 스크립트의 대상이 아니다). `--apply` 는 서비스별로 토큰을 정하고(IAM 쪽 항목 → 서비스 쪽 값 → 새 `openssl rand -hex 32` 순으로 재사용, 32자 미만이면 중단) `~/doro/.env` 의 `DORO_IAM_INTERNAL_TOKENS` 에 `party:`/`games:` 항목 추가, `~/doro-<서비스>/.env` 에 `DORO_IAM_INTERNAL_TOKEN` 기록, 서비스 compose 가 그 변수를 컨테이너에 전달하도록 한 줄 추가(모두 백업 후, 이미 있으면 건드리지 않음). 컨테이너는 `--restart-auth` 를 줬을 때 **auth 만** 재생성(약 25초 다운)하고, 서비스는 다음 코드 배포 때 새 값을 읽는다. `--check` 는 양쪽 토큰 일치 여부까지 비교한다(값은 출력하지 않음).
  - `rotate-credentials.sh [--apply]`(옵션 없으면 사전 점검만): **Postgres `doro_admin` 비밀번호와 MinIO 루트 비밀번호만** 새 무작위 32자로 바꾼다(`POSTGRES_PASSWORD`, 블로그 `.env` 의 `DB_PASSWORD`(블로그가 전용 계정이면 건드리지 않음)·`MINIO_ROOT_PASSWORD`). 사전 점검: 컨테이너·`.env` 존재, 서비스 healthy, 현재 비밀번호로 DB 접속, 디스크 2GB↑. 전 DB `pg_dump`(복원 목록 검증)와 `.env` 백업 → `ALTER USER` → Doro 스택 재기동 → blog/MinIO 재기동 → 검증(새 비밀번호 허용·옛 비밀번호 거부·헬스 200). 3단계 이후 실패하면 `.env` 와 DB 비밀번호를 자동 원복하고 재기동한다. **범위**: 이 스크립트가 직접 갱신하고 재기동하는 것은 Doro 스택과 블로그의 `.env` 뿐이다. 다른 서비스가 `doro_admin` 자격으로 접속한다면 그 서비스의 `.env` 는 따로 갱신해야 하며, 서비스별 전용 계정(`split-db-accounts.sh`)을 쓰는 서비스는 이 회전의 영향을 받지 않는다.
  - `enable-totp-encryption.sh --check|--apply`: `DORO_IAM_TOTP_ENCRYPTION_KEY` 를 만들어 적용하고(이미 있으면 덮어쓰지 않고 중단) `~/ops/backup.sh backup` 으로 DB 를 백업한 뒤 auth 재생성 → 기동 때 평문 TOTP 시크릿이 암호문으로 이전된다. 이전 시작 전 실패만 자동 복구하고 이전 뒤 실패는 키를 지우면 2FA 가 막히므로 되돌리지 않는다. `bootstrap-first-admin.sh --check|--enable|--disable`: 일회용 `DORO_IAM_BOOTSTRAP_TOKEN` 을 넣고/빼며 auth 를 재생성(최고 관리자가 이미 있으면 `--enable` 은 중단). `unlock-account.sh --check|--apply <이메일> [--restart-auth]`: DB 의 계정 전체 잠금(`credentials.locked_until`)만 풀고 (계정, IP) 메모리 잠금은 `--restart-auth` 일 때만 초기화, 정지(`SUSPENDED`)는 풀지 않는다. `split-db-accounts.sh` 는 §1, `guard-reconcile.sh`·`install-guard-reconcile-cron.sh` 는 §6.6.
---

## 14. 변경 이력

- **주의(2026-10-22)**: 이 갱신의 기준은 `bb03743` 이다. 이후 main 에 들어간 OAuth 관련 변경(third-party OIDC e2e 작업 #50: OAuth 세션 scope(`V13__session_oauth_scope.sql`), OIDC 엔드포인트와 동의 오류 처리)은 이 문서에 반영하지 않았다 `[미검증]`: §5 는 그 변경 전 기준이다. 코드가 맞다.

- **2026-10-20 (2차)** — `[미검증]` 항목을 코드·테스트·스크립트 본문 읽기로 해소했다(서버 접속·실행·시크릿 열람 없음). **해소**: ① 내부 API 두 경로의 컨트롤러·`InternalApiAuthFilter` 순서·응답 DTO·정렬/한도/오류 동작과 이를 고정하는 테스트(`InternalApiAuthFilterTest`, `InternalUserStatusTest`, `AccountDeletionTest`)(§2.6) ② `doro-bff` 의 저장소(PostgreSQL 뿐)·`V1__bff_init.sql` 3테이블·쿠키/CSRF 모델·설정 키·`/internal/auth` 동작·정리·탈퇴 동기화, 세 서비스의 `embedded`/`gateway` 스위치 구현(§1.1·§4.7) ③ `auth`·`guard`·`bff` `FlywayConfig` 모두 `migrate()` 만(§6.1) ④ 운영 스크립트 본문(`set-guard-namespaces`·`enable-bff`·`set-bff-mode`·`enable-deleted-sync`·`rotate-credentials`·`split-db-accounts`·`enable-totp-encryption`·`bootstrap-first-admin`·`unlock-account`·`install-guard-reconcile-cron`)의 동작·플래그·안전장치(§1·§13) ⑤ doro-party 튜플 삭제 아웃박스 동작과 `guard-reconcile.sh` 분류·안전장치(§6.6) ⑥ `gateway/nginx.conf` 전문을 라우팅 문서와 대조(§13) ⑦ `@DoroGuard` 의 self-invocation 한계(프록시 기반, §4.3), 포털의 OAuth 세션 표시(§8-B 6번).
  **고친 것(이전 본문이 틀렸거나 부정확했던 것)**: ① "게이트웨이를 거친 요청은 404" 는 IAM 쪽 동작이고 공개 주소의 `/internal/...` 은 게이트웨이의 마지막 `location /` 에서 `301 /blog/internal/...` 로 가 IAM 에 닿지 않는다(§2.6) ② "쿠키가 있으면 클라이언트가 보낸 `Authorization` 은 버린다" → BFF 가 토큰을 돌려준 요청에서만 덮어쓰고, 토큰이 없으면 클라이언트 값이 통과한다(§1.1). BFF 클래스의 주석과 `BFF.md` 의 같은 서술은 이와 어긋난다 ③ `deleted-users` 의 `since` 는 포함(`>=`), `limit` 은 1~500 으로 잘림, 형식 오류 400 을 명시 ④ `[코드]` 로만 적혀 있던 "CSP 강제" 에 `/games/` 는 Report-Only 라는 예외 추가(§13).
  **여전히 서버 상태·런타임이라 `[미검증]`** (각 항목 옆에 읽기 전용 확인 명령을 적었다): 서비스별 운영 모드(`set-bff-mode.sh <서비스> --check`)(§1.1), 서버에 적용된 DB 계정 분리(`split-db-accounts.sh --check`)(§1), Guard 네임스페이스 제한 적용 여부(`set-guard-namespaces.sh --check`)(§13), 내부 API 의 운영 모드·실제 응답(토큰 없는 호출이 401 인지, §2.6), `guard-reconcile` cron 설치·마지막 결과(`install-guard-reconcile-cron.sh --check`)(§6.6), 서버의 게이트웨이 설정이 저장소와 같은지(`diff`, §13), 포털 세션 탭의 실제 화면(§8-B 6번). 이전 항목의 "`bff` 저장소·마이그레이션 세부, 게이트웨이 `nginx.conf` 본문" 은 이번에 해소됐다.
  서버에서만 확인할 수 있는 항목(운영 모드, DB 계정 분리·네임스페이스 제한 적용 상태, 내부 API 직접 호출 응답, cron 설치·결과, 게이트웨이 사본 일치, 포털 화면)의 결과는 이 문서에 적지 않는다. 각 항목 옆의 확인 명령으로 직접 확인한다.
- **2026-10-20** — 커밋 `bb03743` 기준으로 `12d6582` 이후 변경을 반영했다(검증 범위·미조사는 §0). 고친 것(코드가 바뀌어 본문이 틀렸던 것):
  - **`@DoroGuard` 와 Guard 장애(§4.3)**: 이전 본문은 "장애도 `false` → 403" 이었으나 애스펙트가 `checkOrThrow` 를 쓰도록 바뀌어 **503 `GUARD_UNAVAILABLE`**(입력 오류 500 `GUARD_INVALID_REQUEST`, 서비스 토큰 오설정 500 `GUARD_MISCONFIGURED`)이다. §9·§10 의 같은 서술도 고쳤다.
  - **세션 상한 기본값**: 10 → **20**(`application.yaml`·compose; `SessionService` 의 `@Value` 폴백만 10).
  - **Guard REST 서비스 인증 범위(§6.1)**: "`/api/v1/guard/**` 만" → 공개 허용 목록 밖의 **모든 경로**, 디코딩·정규화한 경로로 판정. `/health` 는 토큰 필요, 헬스는 `/actuator/health`.
  - gRPC 1.68.1 → **1.80.0**(`guard`·`sdk`), Guard 는 Redis 환경변수를 받지 않음(compose).
  - **IAM→Guard 튜플 동기화(§6.5·§6.6)**: 역할 변경은 실패를 삼키지 않고 503 `GUARD_UNAVAILABLE` + DB 롤백, 가입·유예 복구는 best-effort, 영구 탈퇴는 튜플 삭제 후 익명화, 기동 동기화는 `DELETED` 제외.
  - **통합 로그인 구도(§1.1)**: "현재 포털·블로그가 인앱 로그인" → 포털만 인앱, 블로그·파티·게임은 **OAuth BFF**(서비스별 `embedded`/`gateway` 모드).
  - §1 DB 서술: `doro_bff` 추가, 서비스별 DB 계정(`*_DB_USER`), Guard·BFF 는 Redis 미사용.
  추가한 것(새 동작):
  - **doro-bff 분리와 gateway 모드**(§1·§1.1·§4.7·§8-A-7·§9), SDK `DoroTokenVerifier`·`DoroUser.clientId`·BFF/탈퇴 동기화 모듈(§4.1·§4.2·§4.7), 내부 API 절(§2.6: `deleted-users`, `users/{id}/status`).
  - IAM: 계정 상태는 비밀번호 확인 뒤에 공개·탈퇴 유예 로그인 복구·관리자는 USER 로 내린 뒤에만 탈퇴·익명화 순서(§2.2), `lookup` 이미지 상한, 관리자 목록 서버 페이지네이션(`page/size/q/filter`, `X-Total-Count`)과 `summary`, `POST /oauth2/revoke`·`GET /oauth2/client-info`(§5.2), 503 `SERVER_BUSY`(해싱 대기 줄)·`GUARD_UNAVAILABLE`(§2.1), 요청 제한 확대(부트스트랩·비밀번호 변경·폐기, IPv6 /64, 인코딩 경로 우회 차단, §2.4), JWT 키 저장소 장애 시 기동 중단(§3.2), OAuth 세션을 일반 리프레시 엔드포인트에서 거부(§2.2).
  - Guard: 호출자별 네임스페이스는 이미 있었고(§6.1) 이번엔 REST 보호 범위·Flyway `repair` 제거·배치/DSL 상한(기존)을 대조했다. 서비스 쪽 튜플 삭제 아웃박스 참조(doro-party)와 정합성 점검 스크립트(§6.6).
  - CI/CD 요약 갱신(§13): 병렬 테스트 작업, 배포 후 스모크·롤백·알림, 새 운영 스크립트 목록.
  - **`USER_GUIDE.md` 와 코드의 어긋남(이번에 발견, 이 문서가 맞음)**: `USER_GUIDE.md` §4 의 일부(Guard 장애·토큰 오류가 `@DoroGuard` 에서 403 으로 보인다는 서술, "`DoroGuardUnavailableException` 은 `checkOrThrow` 직접 호출 때만 발생", 트러블슈팅 표의 "Guard가 ENFORCE 인데 서비스가 모두 403")는 위 `@DoroGuard` 변경 전 동작이다. 코드가 맞다.
  - **여전히 `[미검증]`**: 서비스별 운영 모드(`embedded`/`gateway`), 서버에 적용된 DB 계정 분리·네임스페이스 제한 상태, 내부 API 응답의 실제 호출 결과, `bff` 저장소·마이그레이션 세부, 게이트웨이 `nginx.conf` 본문. (같은 날 2차 검증에서 `bff` 저장소·마이그레이션과 `nginx.conf` 본문은 해소됨, 위 항목 참고.)
- **2026-10-15** — 내부 API `GET /internal/v1/users/{userId}/status`(계정 상태 `ACTIVE`/`SUSPENDED`/`PENDING_DELETION`/`DELETED`, 개인정보 없음, 서비스 토큰 필수, 게이트웨이 경유 요청은 404) 추가: 블로그 API 키처럼 IAM 세션 없이 오래 쓰는 자격 증명이 정지·탈퇴 유예 동안 쓰이지 않게 하는 데 쓴다. OAuth 세션은 서비스별 3기기, IPv6 는 /64 로 묶어 제한, `lookup` 이미지 크기 제한.
- **2026-10-08** — 내부 API(`/internal/v1/deleted-users`) 호출자 인증 추가: 호출자별 서비스 토큰(`X-Doro-Service-Token`, `DORO_IAM_INTERNAL_TOKENS`), 모드 `DORO_IAM_INTERNAL_AUTH_MODE`(기본 ENFORCE), 게이트웨이 경유 요청은 항상 404. 블로그는 `DORO_IAM_INTERNAL_TOKEN` 으로 토큰을 보낸다.
- **2026-10-02** — 현재 코드(`12d6582`) 기준으로 본문 전체 재작성. 2026-09-30 본문(커밋 `84b8da7` + working tree)과 부록(`hardening/phase1` 브랜치 메모)을 합치고 각 항목을 다시 검증했다. 부록의 운영 메모는 §13 으로 줄여 옮겼다.
  - **해결됨(2026-09-30 본문에서 결함으로 적었던 것)**:
    - 액세스 토큰 24시간 → 기본 **15분**(`DORO_IAM_ACCESS_TOKEN_TTL_SECONDS`).
    - 로그아웃: 인증 없이 `sessionId` 만으로 종료 → **Bearer 필수 + 소유자 검증**. `DELETE /sessions/{id}` 의 소유자 검사 없음/리프레시 토큰·킬스위치 미처리 → **소유자 검사 + 리프레시 폐기 + 킬스위치**(단일 진입점 `SessionRevocationService`).
    - 2FA: `setup` 즉시 활성화(계정 잠김 위험) → **pending 저장 + `verify` 로 활성화**, `disable` 은 **코드 필요**.
    - IAM CORS `*` + credentials → **명시적 Origin 패턴 목록**, 베어 `*` 는 기동 거부.
    - OAuth: JSON 만/등록소 없음/인메모리 코드/시뮬레이션 동의 화면 → **실제 인가 코드+PKCE 플로우**, 클라이언트 레지스트리(V6)·redirect_uri 검증, form+JSON 토큰 엔드포인트, `refresh_token` grant, `id_token`/`userinfo`/discovery, Redis 코드 저장소, OAuth 토큰 격리(`cid`, `role=USER`, IAM 은 `userinfo`/`sessions/current` 에서만 인정, `id_token` 은 액세스 토큰 아님).
    - 세션: 사용자당 최대 활성 세션 설정만 있고 미적용 → **적용(오래된 순 종료)**, 슬라이딩 만료, 같은 기기 대체 시 리프레시/킬스위치까지 폐기. 이메일 대소문자 정규화(신규 가입부터 소문자 저장, 조회/중복 검사는 대소문자 무시).
    - JWT 키: 평문 Redis 만 → **선택적 AES-GCM 암호화**, 이전 공개키 로테이션 중복 게시, JWKS `n`/`e` 최소 길이 표현(선행 0x00 제거).
    - SDK: `iss`/`aud` 미검증·킬스위치 미확인 → **issuer 검증 모드, audience, 폐기 확인(OFF 기본)+백오프**, `exp` 필수·RS256 고정, JWKS 캐시 evict·비동기 갱신·prefetch, 쿠키 토큰 소스, **기본 예외 어드바이스(401/403/503)**, `*OrThrow`/`expand`, `aud` 있는 토큰 거부, 서비스 토큰 전송.
    - Guard: 무인증 → **서비스 토큰 인증(OFF/WARN/ENFORCE, 호출자별·`schema-write`)**, 튜플/스키마 **검증 모드**, **Expand RPC 구현**, 실제 평가 깊이(`depth`) 반환, 잘린 평가 캐시 금지·차집합 fail-closed, 커밋 후 캐시 무효화, 스키마 등록 후 캐시 무효화, 다중 인스턴스 **스키마 주기 갱신**, 캐시 TTL/크기 설정, Redis 의존성 제거, 튜플 중복 방지(V2 정리·V3 부분 유니크 인덱스).
    - 요청 제한(IP, 429+`Retry-After`), 신뢰 프록시 기반 클라이언트 IP, 역할 변경·비밀번호 변경·2FA 초기화 시 세션 종료, 관리자 목록 Guard 최종 판정.
    - 게이트웨이: CSP 는 Report-Only → **강제**, `/loki/` 는 관리자 전용(`auth_request`), `/media/` sandbox CSP, `X-Real-IP`/`X-Forwarded-For` 덮어쓰기, `= /oauth2/consent` 포털 라우트.
    - CI: 테스트 없이 배포 → **Docker 안에서 테스트 후 배포**(`scripts/ci-test.sh`, `skip_tests`), 필수 `.env` 없으면 중단.
    - 포털: 모든 계정 로그아웃(`logoutAll`), OAuth 동의 후 복귀 흐름(`consentReturn`), 리프레시 실패 구분·Web Locks.
  - **여전히 열린 것**: §9 목록.
- **2026-09-30** — 최초 작성(커밋 `84b8da7` + working tree 기준) 및 `hardening/phase1` 부록 추가.
