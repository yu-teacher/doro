# Doro 인증·인가 연동 가이드 (에이전트용)

> **이 문서의 목적**: Doro IAM(인증) / Doro Guard(인가) / Doro SDK 를 사용하는 프로젝트에서 작업하는 AI 에이전트가, **다른 문서를 믿지 않고도** 실제 동작에 맞게 연동하도록 돕는다.
>
> **왜 필요한가**: 사용자 문서(`USER_GUIDE.md`)와 README 는 코드 변경을 따라가지 못해 어긋난 적이 많다. 이 문서는 **소스를 직접 읽고** 검증한 사실만 적었다. 이 문서와 코드가 다르면 **코드가 맞다.**

## 0. 출처와 신뢰 범위

| 항목 | 내용 |
|---|---|
| 검증일 | 2026-10-02 |
| 검증 대상 | `Doro` 저장소 커밋 **`12d6582`** (`git -C Doro rev-parse --short HEAD`). 검증 시점에 코드 쪽 working tree 변경은 없었다(문서와 `.claude/` 제외). |
| 참조 구현 | `/Users/yusm/Documents/workspace/doro-blog` (SDK 를 실제로 쓰는 서브 서비스). 이번 검증에서 **다시 읽은 것은 파일 존재, SDK 의존성 방식, 스키마 병합 초기화의 호출 순서, 서비스 토큰 헤더뿐**이다. 그 외 doro-blog 동작은 `[미검증]` |
| 표기 | **[코드]** 소스에서 직접 확인 / **[미검증]** 추론이거나 실행해 보지 않음 |
| 미조사 | `doro-menu`, `server-secret`(시크릿이라 열지 않음), 배포 서버의 실제 상태, 런타임 실행(전부 코드 읽기로만 검증했다. 테스트·기동은 하지 않음) |

**Doro 가 바뀌면 이 문서는 낡는다.** 코드를 수정하기 전에 §11 의 빠른 검증 명령으로 핵심 전제를 다시 확인할 것. 변경 내역은 §14.

---

## 1. 큰 그림

```
브라우저 ──▶ [Gateway nginx :443] ──▶ Portal(web) / IAM(auth) / 블로그 / 메뉴 …
                                      │
서브서비스 ──(JWKS 조회, 로컬 서명검증)──▶ IAM  :8080  /.well-known/jwks.json
서브서비스 ──(선택: 세션 폐기 확인)──────▶ IAM  :8080  GET /api/v1/sessions/current
서브서비스 ──(gRPC :9090 / REST :8081)──▶ Guard
IAM ──(REST, 관리자 판정·역할 튜플 동기화)──▶ Guard
```

| 구성요소 | 포트 | 역할 |
|---|---|---|
| IAM (`auth`) | 8080 | 가입/로그인/2FA/세션/토큰 발급, JWKS, OAuth 2.1/OIDC, 관리자 API |
| Guard (`guard`) | 8081 REST, 9090 gRPC | Zanzibar 방식 ReBAC. 튜플 저장 + `Check`/`Expand` |
| SDK (`sdk`) | (라이브러리) | JWT 로컬 검증 필터, `@DoroGuard`, `@CurrentDoroUser`, gRPC 클라이언트 |
| Portal (`web`) | 3000(→컨테이너 80) | 통합 계정 센터 UI (React). 로그인·계정·로그 뷰어·OAuth 동의 화면 |
| Gateway | 80/443 | nginx 리버스 프록시. 모든 서비스를 **같은 origin** 으로 묶음. 규칙은 `docs/GATEWAY_ROUTING_RULES.md` |

- 서비스별 **독립 DB** 규칙: IAM=`doro_auth`, Guard=`doro_guard`(compose 기준), 서브서비스=`service_{name}`. [코드: `docker-compose.yml`, `AGENTS.md`]
- DB 는 공용 PostgreSQL 컨테이너 하나에 여러 DB. Redis 는 **IAM 이 쓴다**(JWT 키, 킬스위치, OAuth 인가 코드). **Guard 는 Redis 를 쓰지 않는다**(compose 가 Redis 환경변수를 넘기지만 무시됨). [코드]
- 호스트 포트 바인딩 기본값: Postgres/Redis/Guard/Loki/Grafana 는 `127.0.0.1`, auth(8080)·web(3000)은 `0.0.0.0`(`*_BIND` 로 변경). 운영에서는 외부에 22/80/443 만 연다. [코드: compose, `.env.example`]

### 1.1 "통합 로그인"은 두 가지 방식이 공존한다

1. **인앱 로그인 (기본, 현재 포털·블로그가 사용)**: 프론트엔드가 같은 origin(게이트웨이)의 IAM API 를 직접 호출해 로그인하고 액세스/리프레시 토큰을 **브라우저 localStorage** 에 저장한다(포털은 `doro_auth_accounts` 키로 여러 계정). 서브서비스 백엔드는 `Authorization: Bearer <JWT>` 를 받아 JWKS 로 검증한다. [코드: `web/src/store/authStore.ts`, `web/src/api/*`]
2. **OAuth 2.1 인가 코드 + PKCE / OIDC (외부·별도 origin 앱용)**: 리다이렉트 방식 SSO. 클라이언트 등록(`oauth_clients`), 포털 동의 화면, 토큰/리프레시/`id_token`/`userinfo` 가 구현돼 있다(§5). **공개 클라이언트(PKCE)만** 지원한다.

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
- OAuth 토큰 엔드포인트의 **form 요청** 응답/오류는 RFC 6749 형식이다(§5).

### 2.2 엔드포인트

`/api/v1/auth/**` 는 **인증 없이 접근할 수 있는 경로**(permitAll)다. 단 `2fa/*`, `logout` 은 컨트롤러가 Bearer(인증된 사용자)를 요구해 없으면 401 `UNAUTHORIZED` 를 낸다. 그 외 `/api/v1/**` 는 인증 필요, `/api/v1/admin/**` 는 JWT 역할 ADMIN 이상 + 서비스 안에서 Guard 재판정. [코드: `SecurityConfig`]

| 메서드·경로 | 요청 | 응답 `data` | 비고 |
|---|---|---|---|
| `POST /api/v1/auth/signup` | `{email, password(8~64자), name(2~50자)}` | `{userId}` (201) | 이메일은 trim+소문자로 저장, 중복 검사는 대소문자 무시(409 `EMAIL_ALREADY_EXISTS`) |
| `POST /api/v1/auth/login` | `{email, password, deviceInfo?}` | `{requires2fa, tempTicket?, tokens?}` | `clientIp/userAgent` 는 서버가 추출. 2FA 계정은 `requires2fa:true`+`tempTicket`, 아니면 `tokens` |
| `POST /api/v1/auth/2fa/login` | `{tempTicket, code(6자리 숫자), deviceInfo?}` | `TokenResponse` | |
| `POST /api/v1/auth/2fa/setup` | (Bearer) | `{secret, qrUri}` | 시크릿을 **대기(pending)** 로만 저장. 이미 활성이면 400 |
| `POST /api/v1/auth/2fa/verify` | (Bearer) `{code}` | 없음 | 대기 중이면 **코드 확인 후 활성화**, 이미 활성이면 코드 검증만. 실패는 잠금 카운트에 합산 |
| `POST /api/v1/auth/2fa/disable` | (Bearer) `{code}` | 없음 | **현재 OTP 코드로 재인증**해야 해제. 실패는 잠금 카운트에 합산 |
| `POST /api/v1/auth/token/refresh` | `{refreshToken}` | `TokenResponse` | RTR. 이전 토큰 즉시 폐기 |
| `POST /api/v1/auth/logout[?sessionId=<uuid>]` | (Bearer) | 없음 | **Bearer 필수**. `sessionId` 생략 시 토큰의 `sid`(현재 세션). **본인 세션만** 종료(남의 세션/없는 세션은 404 `SESSION_NOT_FOUND`) |
| `POST /api/v1/auth/lookup` | `{email}` | `{email,name,profileImageUrl}` | 없으면 404 `USER_NOT_FOUND` → 계정 존재 여부 노출(요청 제한으로만 완화) |
| `GET /api/v1/users/me` | (Bearer) | `{id,email,name,profileImageUrl,status,role,hasTotp,createdAt}` | |
| `PATCH /api/v1/users/me` | (Bearer) `{name?, profileImageUrl?}` | 프로필 | 이미지 길이 상한 `doro.iam.profile.image-max-length`(기본 1048576자) |
| `PUT /api/v1/users/me/password` | (Bearer) `{currentPassword, newPassword(8~64자)}` | 없음 | 성공하면 **현재 세션을 제외한 모든 세션 종료** |
| `GET /api/v1/sessions` | (Bearer) | 활성 세션 목록 | |
| `GET /api/v1/sessions/current` | (Bearer) | 204 | 현재 토큰의 세션이 DB 기준 유효한지. 무효면 401 `SESSION_EXPIRED`, 계정 정지면 403. SDK 의 폐기 확인이 사용(§4.1) |
| `DELETE /api/v1/sessions/{id}` | (Bearer) | 없음 | **소유자 검사**(타인 세션은 404) + 리프레시 토큰 폐기 + 킬스위치 |
| `POST /api/v1/sessions/revoke-others?currentSessionId=` | (Bearer) | 없음 | 현재 세션만 남기고 종료(소유자 검사 포함) |
| `GET /api/v1/admin/users` | (Bearer, ADMIN+) | 사용자 목록 | JWT 역할 + Guard `system:doro#admin` 으로 최종 판정 |
| `PATCH /api/v1/admin/users/{id}/role` | (Bearer) `{role}` | 프로필 | Guard `system:doro#manage_roles`. 본인 역할 변경 불가. 변경 후 대상의 **모든 세션 종료** |
| `DELETE /api/v1/admin/users/{id}/2fa` | (Bearer) | 없음 | Guard `user:{id}#can_reset_2fa`. 성공하면 대상의 모든 세션 종료 |
| `GET/POST/DELETE /api/v1/admin/oauth/clients` | (Bearer, ADMIN+) | OAuth 클라이언트 | 등록/목록/비활성화. §5.3 |
| `GET /api/v1/admin/authz` | (Bearer, ADMIN+) | 204 | 게이트웨이 `auth_request` 전용(로그 뷰어 보호). Guard `system:doro#admin` 최종 판정 |
| `GET /.well-known/jwks.json` | 없음 | JWKS(응답 봉투 없음) | 서브서비스가 사용 |
| `GET /.well-known/openid-configuration` | 없음 | OIDC 메타데이터 | §5 |
| `GET /health`, `/actuator/health` | 없음 | 상태 | `/actuator/**` 나머지는 ADMIN 필요 |

`TokenResponse` = `{accessToken, refreshToken, tokenType:"Bearer", expiresIn, sessionId, userIndex}` (+ OAuth 교환 응답에서만 `idToken`, `scope`). [코드]

**존재하지 않는 것**: `/api/v1/auth/accounts/add`, `/accounts/switch/{uidx}`, `/2fa/enable`, `/login/2fa`. 다중 계정 "Google 스타일 전환"은 **서버가 아니라 포털 프론트엔드**(여러 계정 토큰을 localStorage 에 보관)가 한다. 서버의 `uidx` 는 사용자별 활성 세션 순번(max+1)일 뿐이다. 사용자 상태(`LOCKED`/`SUSPENDED`)를 바꾸는 API 와 첫 관리자 부트스트랩 API 도 없다. [코드]

### 2.3 로그인 보안 동작 [코드]

- 비밀번호 **5회 연속 실패 → 15분 잠금 → HTTP 403** `ACCOUNT_LOCKED`. 2FA(OTP) 실패와 `2fa/verify`·`disable` 실패도 같은 카운트에 합산된다. 실패 카운트는 원자적(조건부 UPDATE)이다.
- 2FA 티켓: 5분 유효, OTP 5회 실패 시 폐기. **인메모리 저장**이라 IAM 재시작/다중 인스턴스에서 깨진다(보관 상한 `max-pending-tickets` 초과 시 429).
- OTP: 시간 오차 ±1 스텝 허용, **같은 스텝 재사용 방지**(인메모리 맵).
- 비밀번호는 Argon2id (`CustomArgon2PasswordEncoder`).
- **세션**: 사용자당 동시 활성 세션 **상한(기본 10, `DORO_IAM_SESSION_MAX_ACTIVE_PER_USER`)** — 초과하면 가장 오래된 세션부터 종료. **같은 IP+User-Agent** 의 이전 활성 세션은 새 로그인 시 자동 종료(OAuth 세션은 클라이언트별 마커로 구분). 종료는 모두 `SessionRevocationService` 를 거쳐 **세션 비활성화 + 리프레시 토큰 폐기 + 킬스위치 발행**이 한 흐름에서 일어난다. 동시 로그인에서는 상한을 잠깐 넘을 수 있다(직렬화 안 함).
- 세션 만료는 **슬라이딩**: 리프레시가 성공할 때마다 `expiresAt = now + 30일`. 만료 세션은 주기적으로 정리된다.
- 리프레시 토큰 재사용(이미 회전된 토큰 재제시)은 공격으로 판정해 해당 패밀리와 세션을 종료하고 400 `TOKEN_REUSE_DETECTED`. 회전은 조건부 UPDATE 로 원자적이라 동시 요청 중 하나만 성공한다. 다중 탭 경합을 허용하는 유예(`doro.iam.jwt.refresh-reuse-grace-seconds`)는 **기본 0(꺼짐)**.
- 비밀번호 변경·역할 변경·관리자 2FA 초기화 시 관련 세션이 종료된다(위 표).

### 2.4 요청 제한과 클라이언트 IP [코드]

- IP 단위 고정 윈도우, 초과 시 **429 + `Retry-After`**: 로그인/2FA 로그인 10분당 20회, 계정 조회 10분당 30회, 가입 1시간당 10회, `POST /oauth2/token` 및 **Bearer 없는** `GET /oauth2/authorize` 10분당 60회. `doro.iam.rate-limit.*` 로 조정, `DORO_IAM_RATE_LIMIT_ENABLED=false` 로 끈다. **단일 인스턴스 메모리 기준**.
- 클라이언트 IP(`ClientIpResolver`): **신뢰 프록시(`doro.iam.trusted-proxies`, 기본 루프백 + 도커 브리지 `172.16.0.0/12`)** 에서 온 연결의 `X-Real-IP` 만 신뢰한다. LAN(192.168/16, 10/8)이나 인터넷에서 온 헤더는 무시. 요청 제한·세션 기록·로그가 같은 규칙을 쓴다. 게이트웨이는 `X-Real-IP`/`X-Forwarded-For` 를 **접속 주소로 덮어쓴다**.

### 2.5 CORS [코드]

`doro.cors.allowed-origin-patterns`(`DORO_CORS_ALLOWED_ORIGIN_PATTERNS`, 쉼표 구분 **명시적 패턴 목록**) 만 허용하고 `allowCredentials(true)`. **베어 `*` 는 기동 시 거부**한다. 기본값은 공개 도메인, 로컬 LAN 주소, `localhost:*`, `127.0.0.1:*`. 허용 헤더는 `Authorization, Content-Type, Accept, X-Trace-Id, X-Requested-With`. Guard 는 브라우저용 CORS 를 제공하지 않는다. 새 Origin 에서 브라우저가 IAM 을 직접 호출해야 하면 이 목록에 추가한다(`permitAll`/`*` 로 우회 금지).

---

## 3. 토큰

| 항목 | 값 | 출처 |
|---|---|---|
| 알고리즘 | RS256, 헤더 `kid`(기본 `doro-iam-key-2026-v1`) | `JwtTokenProvider` |
| **액세스 토큰 TTL** | **900초(15분)** 기본. `DORO_IAM_ACCESS_TOKEN_TTL_SECONDS` 로 조정 | `application.yaml`, compose |
| 리프레시 토큰 TTL | 2592000초(30일), 불투명 랜덤값(SHA-256 해시로 DB 저장). 회전 때마다 30일 연장 | `RefreshTokenService` |
| 세션 무활동 만료 | 30일 슬라이딩. 사용자당 최대 활성 세션 10 | `application.yaml` |
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
- 키 출처 우선순위: 설정 PEM(`private-key-pem`/`public-key-pem`) → Redis(`doro:iam:jwt:keypair:*`) → 신규 RSA-2048 생성 후 Redis 저장.
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
- **Java 25 툴체인 필수**(SDK 가 25 로 컴파일). Spring Boot 4.1.0(`starter-web`, `spring-aspects`), JJWT 0.12.6, gRPC 1.68.1. [코드: `sdk/build.gradle`]
- **Spring Security 와 무관하게 동작**한다. SDK 는 Spring Security 컨텍스트를 채우지 않는다. 함께 쓰면 두 인증 체계가 따로 논다.
- 자동설정(`DoroAutoConfiguration`)이 필터·AOP·gRPC 클라이언트·인자 해석기·기본 예외 어드바이스를 등록한다. 각 빈은 `@ConditionalOnMissingBean`.

### 4.1 `DoroJwtAuthFilter` [코드]
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
- 타입 `DoroUser`(record: `userId, email, sessionId, userIndex, role`), `UUID`, `String` 지원.
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
- AOP 기반이라 **같은 클래스 내부 호출(self-invocation)** 에는 적용되지 않고 **Spring 빈의 `public` 메서드**여야 한다. [미검증(일반 Spring AOP 특성)]
- 애스펙트는 예외를 던지지 않는 `check()` 를 쓴다. 따라서 **Guard 장애도 `false` → 403** 이 된다(§4.6 의 503 은 서비스가 `*OrThrow` 를 직접 호출할 때만 발생).

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

---

## 5. OAuth 2.1 / OIDC — 실제 동작

**인가 코드 + PKCE(S256) 플로우가 구현되어 있다.** 공개 클라이언트(`token_endpoint_auth_methods_supported: none`)만 지원한다. [코드: `OAuth2Controller`, `OAuth2Service`, `OAuthClientRegistry`]

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
| `GET /oauth2/userinfo` | Bearer OAuth 액세스 토큰. `sub, email, email_verified(항상 false), name, picture(http(s) URL 만)`. 세션이 유효해야 함 |
| `GET /.well-known/openid-configuration` | 메타데이터. **`issuer` 및 엔드포인트 URL 이 `DORO_IAM_ISSUER` 값으로 만들어진다**(§9 참고) |

- **grant**: `authorization_code`, `refresh_token`(둘 다 지원). 리프레시 grant 는 **그 `client_id` 의 OAuth 세션에 속한 토큰만** 허용하고(다른 클라이언트·일반 로그인 토큰은 소모하지 않고 거부), 기존 RTR(재사용 탐지 포함)을 쓴다. 리프레시 응답에는 `id_token` 이 없다.
- **`id_token`**: `openid` 스코프가 승인됐을 때만 발급(교환 응답에서). 스코프: `openid`, `profile`, `email`. `scope` 를 생략하면 빈 스코프(= `id_token` 없음, 클레임 없음).
- **세션/토큰**: 코드 교환은 새 세션을 만든다(IP 마커 `OAuth2`, UA `OAuth2 PKCE Flow: <client_id>`). 같은 클라이언트의 이전 OAuth 세션만 교체된다. 사용자당 세션 상한(기본 10)에 포함된다. OAuth 액세스 토큰은 `role=USER` + `cid`(§3).
- **인가 코드 저장소**: `doro.oauth.code-store` = `auto`(기본: 기동 시 Redis 에 닿으면 Redis, 아니면 WARN 후 메모리) | `redis` | `memory`. 메모리 모드는 재시작/다중 인스턴스에서 코드가 유실되고 `max-pending-authorization-codes`(10000) 초과 시 429. `auto` 가 기동 시 Redis 에 닿지 못하면 **재시작 전까지 메모리**를 쓴다.

### 5.3 클라이언트 등록과 모드
- 테이블 `oauth_clients`(Flyway **V6**, V7). 등록: `POST /api/v1/admin/oauth/clients` `{name, redirectUris[1~10], scopes?, clientId?, firstParty?}`(ADMIN + Guard `system:doro#admin`). `redirect_uri` 는 **https 만**(loopback 호스트는 http 허용), 와일드카드/fragment/userinfo/`..` 세그먼트 불가, ≤500자. `clientId` 생략 시 서버가 생성(`[A-Za-z0-9._~-]{1,100}`). `DELETE .../{clientId}` 는 **소프트 삭제**(`is_active=false`). 이후 요청은 `WARN`/`ENFORCE` 에서 거부되고, `OFF` 모드는 레지스트리를 조회하지 않아 영향이 없다 [코드: `OAuthClientRegistry.lookup`].
- `redirect_uri` 는 **문자열 정확 일치**만 인정한다(정규화·부분 일치 없음).
- 클라이언트에 `firstParty: true` 를 주면(관리자 등록 시, 테이블 컬럼 `first_party`, Flyway **V7**) **자사 서비스**로 표시된다. 포털 동의 화면은 이 표시가 있는 클라이언트에는 동의를 묻지 않고 로그인 직후 바로 인가 코드를 요청한다(`GET /oauth2/client-info?client_id=` 로 확인, 로그인 필요, 등록·활성 클라이언트만 응답). 제3자 앱은 기본값(`false`)이라 기존처럼 동의 화면을 거친다. `redirect_uri` 의 정확 일치 검증은 자사 앱에도 똑같이 적용된다.
- `doro.oauth.client-registry-mode`(`DORO_OAUTH_CLIENT_REGISTRY_MODE`, 기본 **`ENFORCE`**):
  - `OFF`: 레지스트리 미사용, 환경변수 허용 목록(`DORO_OAUTH_ALLOWED_REDIRECT_URIS`, 쉼표 구분, 정확 일치)만 사용.
  - `WARN`: **등록된 클라이언트는 레지스트리 규칙**(등록한 redirect_uri/scope 만), **미등록 `client_id` 는 환경변수 허용 목록으로 폴백**(경고 로그).
  - `ENFORCE`: 미등록 `client_id` 는 `invalid_client` 로 거부.
  - 등록돼 있지 않고 허용 목록도 비어 있으면(기본) 인가 요청은 모두 거부된다.
- 서비스가 OAuth 로 붙는 설계가 필요하면: ① 관리자가 클라이언트 등록 ② `ENFORCE` 로 올릴지 운영자와 협의 ③ PKCE(S256) 구현 ④ 받은 액세스 토큰을 서브서비스 API 에 쓰되 권한은 Guard 로 판정.

### 5.4 한계 [코드]
공개 클라이언트·고정 스코프만(클라이언트 시크릿 인증, 스코프별 `userinfo` 응답, 동의 이력 저장 없음), `email_verified` 는 항상 `false`, 인가 코드 재사용 시 토큰 폐기 미구현.

---

## 6. Guard — 실제 규격

### 6.1 서비스 인증 (OFF / WARN / ENFORCE)
`doro.guard.security.mode`(`DORO_GUARD_SECURITY_MODE`, 기본 **`OFF`**) — 설정 없이는 **인증이 없다.** [코드: `ServiceAuthProperties`, `ServiceTokenFilter`, `ServiceTokenServerInterceptor`]
- 토큰은 REST 헤더 `X-Doro-Service-Token`, gRPC 메타데이터 `x-doro-service-token`. 모든 호출자가 토큰을 보내도록 배포한 뒤 `OFF → WARN → ENFORCE` 로 올린다. `WARN` 은 로그만, `ENFORCE` 는 REST 401(`UNAUTHORIZED`)/gRPC `UNAUTHENTICATED`.
- **공유 토큰** `DORO_GUARD_SERVICE_TOKEN`(전환 기간용, 모든 권한 보유). **호출자별 토큰** `DORO_GUARD_SERVICE_TOKENS=auth:<토큰>,blog:<토큰>[:schema-write]`: 이름은 `[a-z][a-z0-9-]{0,31}`, 토큰 32자 이상, 이름/토큰 중복·`shared` 예약, 형식 오류면 **기동 실패**. 비교는 상수 시간.
- **권한(scope)**: `POST /api/v1/guard/schema`(스키마 전체 교체)는 **`schema-write`** 가 있는 호출자(또는 공유 토큰)만 가능. 없으면 ENFORCE 에서 403 `FORBIDDEN`(WARN 에서는 경고만). 운영에서는 스키마를 등록하는 서비스만 갖는다. gRPC 에는 스키마 쓰기가 없다.
- 호출자별 토큰이 설정된 뒤에도 공유 토큰으로 들어오는 호출은 경고(`Guard call with the deprecated shared service token`)가 남는다(정리 대상).
- REST 보호 범위는 `/api/v1/guard/**` 이다(health/actuator/swagger 는 대상 아님). **gRPC·REST 모두 TLS 가 없다** → 신뢰 네트워크 안에서만 쓰고 **절대 외부 노출 금지**. 게이트웨이 nginx 는 Guard 를 프록시하지 않는다(확인함). 호스트 포트 게시는 기본 `127.0.0.1` 이다(`GUARD_BIND`).

### 6.2 REST (`:8081`)
경로는 `/api/v1/guard/**` 이다. 응답은 `{success, data, timestamp}` 봉투, 오류는 IAM 과 같은 `ErrorResponse` 형식(`code` 는 enum 이름). 필드 길이 제한: `namespace/relation/subjectNamespace/subjectRelation` ≤64, `objectId/subjectId` ≤128. REST 와 gRPC 가 같은 한계를 쓴다. **REST 에는 Expand 엔드포인트가 없다**(gRPC 전용).

| 메서드·경로 | 요청 | 응답 `data` |
|---|---|---|
| `POST /api/v1/guard/check` | `{namespace, objectId, relation, subjectNamespace, subjectId, subjectRelation?}` | `{allowed, depth, reason}` (`reason`: `ACCESS_GRANTED`/`ACCESS_DENIED`/`L1_CACHE_HIT`) |
| `POST /api/v1/guard/tuples` | `[ {…튜플…} ]` | `{writtenCount}` |
| `DELETE /api/v1/guard/tuples` | 위와 동일한 배열(본문 있는 DELETE) | `{deletedCount}` |
| `GET /api/v1/guard/schema` | 없음 | **DSL 문자열** (`data` 가 곧 텍스트) |
| `POST /api/v1/guard/schema` | **JSON** `{"dsl": "..."}` | `{version, dsl, active}` |

- 튜플 쓰기는 **멱등**이고 같은 배치 안 중복은 합쳐진다(이미 있으면 건너뜀, `writtenCount` 에 안 잡힘). 직접 튜플은 DB 부분 유니크 인덱스(Flyway `db/vendor/postgresql/V3`)가 중복을 막는다.
- gRPC `doro.guard.v1.GuardService`: `Check`, `WriteTuples`, `DeleteTuples`, `Expand`. 오류는 4xx 성격이면 `INVALID_ARGUMENT`, 그 외 `INTERNAL`. [코드: `doro_guard.proto`, `GuardGrpcService`]
- 헬스: `/health`, `/actuator/health`.

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
`user`(manager, super_manager, can_reset_2fa), `group`, `system`(super_admin, admin, auditor, manage_roles), `folder`, `document`. IAM 이 사용자 가입/역할 변경 때, 그리고 **기동 시 전체 재동기화**로 `system:doro#admin|super_admin` 과 `user:<id>#manager|super_manager` 튜플을 쓴다. [코드: `UserRelationSyncService`]

### 6.6 역할과 인가의 이중 구조
- **JWT `role` 클레임**(`USER/ADMIN/SUPER_ADMIN`)은 IAM DB 의 `users.role` 에서 나오고, IAM 의 `hasAnyRole` 같은 정적 검사에 쓰인다.
- **Guard 튜플**은 IAM 이 역할 변경 시 동기화한다. IAM 의 관리자 API 는 JWT 역할로 1차 거르고 **Guard 로 최종 판정**한다. 역할이 바뀌면 대상의 세션을 모두 종료해 낡은 클레임을 없앤다.
- 워크스페이스 룰은 "역할 분기문 금지, Guard 에 위임"이다. **서브서비스의 관리자 판정은 `guardClient.check("system","doro","admin",userId)` 로 하고 `DoroUser.isAdmin()` 에 의존하지 말 것.** 클레임은 최대 토큰 수명(기본 15분, 폐기 확인을 켜지 않으면 강등 후에도) 낡을 수 있다.
- IAM 의 Guard 호출은 실패 시 **fail-closed** 이지만, 튜플 쓰기 실패는 DB 변경을 되돌리지 않는다(기동 시 재동기화로 복구).

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

1. **DB**: `service_{name}` 을 `docker-compose.yml` 의 `POSTGRES_MULTIPLE_DATABASES`, `.env.example`, `.env` 에 추가. Flyway `{name}_schema_history`, `ddl-auto: validate`.
2. **SDK**: Java **25**, `includeBuild('../Doro')` + `dependencySubstitution`, `-parameters` 컴파일 옵션, `doro.iam.jwks-uri`/`doro.guard.*` 설정. **Spring Security 스타터는 넣지 않는다.** Guard 가 `ENFORCE` 이면 서비스 전용 토큰(`doro.guard.service-token`)을 발급받아 넣는다(운영자에게 요청 — 토큰 값은 코드/로그/문서에 남기지 않음). 선택: `revocation-check`(로그아웃 즉시 반영), `issuer-validation`/`audience`.
3. **스키마**: 서비스 접두사 네임스페이스로 `.doro` 작성 → **§6.3 절차(GET → 병합 → POST)** 로 등록. **절대 자기 타입만 POST 하지 말 것.** 등록 직전에 기존 DSL 을 백업(`GET` 결과를 파일로 저장)해 두면 복구가 쉽다. `schema-write` 권한이 필요하다.
4. **컨트롤러**: 조회는 공개 가능. 변경은 `@DoroGuard` 또는 `@CurrentDoroUser` + `isAuthenticated()` 확인. **`@CurrentDoroUser UUID` 는 비로그인 시 null.** SDK 기본 예외 어드바이스가 401/403/503 을 처리한다(자체 핸들러로 봉투 형식을 맞출 수 있음).
5. **튜플 동기화**: 리소스 생성 시 `writeTuple`, 삭제 시 `deleteTuple` — 가능하면 **`writeTupleOrThrow`/`deleteTupleOrThrow`** 를 쓰거나 반환값 0 을 실패로 취급(로그+재시도/보상). 리소스 저장 후 튜플 쓰기 실패 시 고아 상태가 될 수 있음을 설계에 반영.
6. **게이트웨이**: `Doro/gateway/nginx.conf` 에 `upstream` + `location` 추가(라우팅 우선순위 주의 — `docs/GATEWAY_ROUTING_RULES.md`). **Guard 를 프록시하지 말 것.** 게이트웨이는 CI 로 배포되지 않는다(서버에 직접 반영 + `nginx -t` + reload).
7. **검증**: 빌드, 컨테이너 기동 `healthy`, 실제 엔드포인트 호출(워크스페이스 룰 §5). §11 명령으로 인증/인가를 **직접 확인**.

### B. 서버 렌더링(Thymeleaf 등) 웹앱이 Doro 로 로그인하기

**방법 1 — OAuth 2.1/OIDC 인가 코드 + PKCE (권장, 비밀번호를 직접 다루지 않음)**
1. 관리자가 클라이언트를 등록한다(`POST /api/v1/admin/oauth/clients`, §5.3). 서버 렌더링 앱의 `redirect_uri`(https)를 정확히 등록한다.
2. 앱이 `code_verifier`(43~128자 unreserved)/`code_challenge`(S256)/`state`/`nonce` 를 만들고 사용자를 `/oauth2/authorize` 로 보낸다(§5.1). 로그인·동의는 포털이 처리한다.
3. 콜백에서 `state` 를 검증하고, **서버에서** `POST /oauth2/token`(form, `grant_type=authorization_code`)으로 교환한다. `id_token` 의 `aud`/`nonce`/서명(JWKS)을 검증한다.
4. 액세스/리프레시 토큰은 **`HttpOnly; Secure; SameSite=Lax` 쿠키 또는 서버 세션**에 둔다(JS 노출 금지). 만료 시 `refresh_token` grant 로 갱신하고 **새 리프레시 토큰으로 교체**(RTR — 이전 토큰은 즉시 무효, 동시 갱신 경합 주의, 재사용 감지 시 세션 종료).
5. 서버 쪽 로그인 상태는 앱 자체 세션/쿠키로 관리하고, 서브서비스 API 호출에는 액세스 토큰을 `Authorization: Bearer` 로 보낸다. 이 토큰은 `role=USER`/`cid` 이므로 **권한 판정은 Guard** 로 한다.
6. 로그아웃: 앱 세션/쿠키 삭제. OAuth 액세스 토큰은 IAM 이 인증으로 인정하지 않으므로(§3.1) `POST /api/v1/auth/logout` 에 **쓸 수 없다**. OAuth 세션도 사용자의 활성 세션 목록(`GET /api/v1/sessions`)에 보이므로, 사용자가 **일반 로그인 토큰**(포털)으로 `DELETE /api/v1/sessions/{id}` 로 종료할 수 있다. 클라이언트 쪽에서 OAuth 세션을 종료하는 전용 API 는 없다. [미검증: 포털 UI 에서 OAuth 세션 항목 표시]
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
서브서비스가 포털의 저장된 계정 토큰(`doro_auth_accounts`)으로 로그인하면 같은 리프레시 토큰 계열을 쓴다. 한쪽이 회전하면 다른 쪽 토큰은 폐기되어 **재사용 공격으로 판정**되므로, 갱신 전에 공유 저장소의 더 최근 토큰을 쓰고 갱신 후 되돌려 써야 한다. 프런트 갱신 규칙: 리프레시 실패를 `refreshed / rejected / unavailable` 로 구분해 **서버가 거부(400/401/403/404)한 경우에만 로그아웃**하고, 네트워크 오류·5xx 는 로그인 상태를 유지한다. 여러 탭은 Web Locks 로 직렬화한다. 참고: `Doro/web/src/api/tokenRefresh.ts`, `doro-blog/web/src/api/tokenRefresh.ts`. 포털 "모든 계정 로그아웃"(`web/src/api/logoutAll.ts`)은 계정별로 **자신의 액세스 토큰(필요하면 갱신 후)으로** `POST /api/v1/auth/logout?sessionId=` 를 호출하고, 실패한 계정은 목록으로 사용자에게 알린다(로컬 정리는 계속).

---

## 9. 보안 구성 가이드 (켤 수 있는 기능과 권장 설정)

Doro 의 보안 기능은 **단계적으로 켤 수 있게** 설계돼 있다. 기본값은 기존 서비스가 깨지지 않도록 호환 쪽이고, 운영 환경에서는 아래를 권장한다. 모든 항목은 환경 변수/속성으로 제어하며 되돌리기도 같은 방식이다.

| 기능 | 켜는 방법 | 효과 |
|---|---|---|
| **Guard 서비스 인증** | `DORO_GUARD_SECURITY_MODE=WARN` 으로 호출자를 확인한 뒤 `ENFORCE`. 호출자별 토큰 `DORO_GUARD_SERVICE_TOKENS=auth:<토큰>,blog:<토큰>:schema-write` (`scripts/split-guard-tokens.sh` 가 전환·검증·자동 복구) | 서비스가 아닌 호출 차단, 호출자 식별, 스키마 교체는 `schema-write` 권한이 있는 호출자만 |
| **내부 API 호출자 인증**(`/internal/**`) | `DORO_IAM_INTERNAL_TOKENS=blog:<토큰>[,호출자:<토큰>…]`(토큰 32자 이상 무작위, 형식 오류·짧은 토큰·이름 중복이면 **기동 실패**), 호출자는 헤더 `X-Doro-Service-Token` 으로 보낸다. 모드 `DORO_IAM_INTERNAL_AUTH_MODE`: **ENFORCE(기본)**, 도입 중에는 `WARN`(실패를 경고만 하고 통과), `OFF`. 게이트웨이를 거친 요청(`X-Forwarded-For`/`X-Real-IP`)은 토큰이 맞아도 **404** | 네트워크 격리에만 기대지 않고 호출자를 확인, 토큰 없는 요청은 401(표준 오류 본문), 토큰 값은 로그에 남지 않음 |
| **Guard 튜플/스키마 검증** | `DORO_GUARD_VALIDATION_MODE=WARN` → 로그 확인 → `ENFORCE` | 스키마에 없는 타입/릴레이션 튜플, 오타·미선언 타입 스키마 거부 |
| **JWT 개인키 암호화** | `DORO_IAM_JWT_KEY_ENCRYPTION_SECRET` (AES-256-GCM, 기존 키 자동 이전). 키 회전은 `previous-key-id` + `previous-public-key-pem` | Redis 에 보관하는 서명 키를 저장 시 암호화, 무중단 키 교체 |
| **세션 폐기 즉시 반영** | SDK `doro.iam.revocation-check: WARN` → `ENFORCE` | 로그아웃/세션 종료가 서브 서비스에 즉시 반영 (캐시·백오프·fail-open 설정 제공) |
| **OAuth 클라이언트 등록 강제** | `DORO_OAUTH_CLIENT_REGISTRY_MODE=ENFORCE` | 등록된 `client_id` 와 `redirect_uri` 정확 일치만 허용 |
| **audience / issuer 검증** | SDK `doro.iam.audience`, `doro.iam.issuer-validation: ENFORCE` | 토큰 대상·발급자 확인 |
| **OIDC 공개 URL** | `DORO_IAM_ISSUER` 를 공개 URL 로 | 외부 OIDC 클라이언트의 discovery 가 올바른 주소를 가리킴 |
| **세션/요청 제한** | `DORO_IAM_SESSION_MAX_ACTIVE_PER_USER`, `DORO_IAM_RATE_LIMIT_*` | 사용자당 활성 세션 상한, IP 단위 요청 제한 |

연동 코드를 쓸 때의 동작 방식(필터는 요청을 막지 않는다, `@CurrentDoroUser UUID` 는 비로그인 시 `null`, `writeTuple` 은 실패 시 `0`, `@DoroGuard` 의 Guard 장애는 거부로 처리 등)은 §4·§6·§10 에 있다. 이 가이드와 코드가 다르면 **코드가 맞다.**

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
- ❌ `writeTuple` 반환값을 무시하지 말 것(또는 `writeTupleOrThrow` 사용).
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

# 7) OAuth: 미등록 client/redirect 거부 확인 (등록 없이 WARN 모드 + 허용 목록 비어 있음 → 400)
curl -s -o /dev/null -w '%{http_code}\n' \
  'localhost:8080/oauth2/authorize?response_type=code&client_id=probe&redirect_uri=https://example.test/cb&code_challenge=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&code_challenge_method=S256'
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
| 에러 코드 | `auth/.../common/exception/{ErrorCode,GlobalExceptionHandler}.java` |
| Guard 엔진 | `guard/.../core/engine/{CheckEngine,ExpandEngine}.java` |
| DSL 파서 | `guard/.../core/dsl/parser/DslParser.java` |
| 스키마 등록 | `guard/.../core/dsl/service/SchemaService.java` |
| 튜플·검증 | `guard/.../domain/tuple/service/{TupleService,TupleSchemaValidator}.java`, `common/validation/*` |
| 서비스 인증 | `guard/.../config/{ServiceAuthProperties,ServiceTokenFilter,ServiceTokenServerInterceptor}.java` |
| Guard REST/gRPC | `guard/.../interfaces/api/{Check,Tuple,Schema}Controller.java`, `interfaces/grpc/GuardGrpcService.java` |
| 기본 스키마 | `guard/src/main/resources/schema.doro` |
| gRPC 계약 | `sdk/src/main/proto/doro_guard.proto` (guard 에도 사본 있음) |
| SDK 필터·AOP·클라이언트 | `sdk/.../security/filter/DoroJwtAuthFilter.java`, `security/jwks/JwksKeyProvider.java`, `security/revocation/SessionRevocationChecker.java`, `aop/DoroGuardAspect.java`, `client/DoroGuardClient.java`, `web/DoroExceptionHandlerAdvice.java`, `config/DoroProperties.java` |
| 포털 | `web/src/api/{client,tokenRefresh,logoutAll}.ts`, `web/src/pages/OAuthConsentPage.tsx`, `web/src/utils/{oauthConsent,consentReturn}.ts` |
| 참조 서비스 | `doro-blog/`(`infra/guard/BlogSchemaInitializer`, `blog-schema.doro`, `GlobalExceptionHandler`, `infra/guard/GuardTuples`) |
| 라우팅 규칙 | `Doro/gateway/nginx.conf`, `Doro/docs/GATEWAY_ROUTING_RULES.md` |
| 빌드·배포 | `docker-compose.yml`, `.github/workflows/deploy.yml`, `scripts/*.sh` |
| 엔지니어링 룰 | `workspace/AGENTS.md`, `Doro/AGENTS.md` |

---

## 13. 운영 참고

- **CI/CD** (`.github/workflows/deploy.yml`): `main` 푸시 시 **테스트 → 배포** 순서다. `test` 작업이 `scripts/ci-test.sh backend`(auth·guard·sdk `./gradlew test`, `gradle:9.5.1-jdk25` 컨테이너)와 `scripts/ci-test.sh web`(`npm ci`, `npm test`, `tsc -b`)을 컨테이너 안에서 실행하고, 통과해야 `deploy` 작업이 돈다. 수동 실행(`workflow_dispatch`)의 `skip_tests` 로 건너뛸 수 있고 배포는 동시에 하나씩만 실행된다.
- **게이트웨이 설정**은 CI 로 갱신되지 않는다: 수동 반영 후 `nginx -t` → reload(상세는 `docs/GATEWAY_ROUTING_RULES.md`). 보안 헤더와 **CSP 는 강제**(`Content-Security-Policy`) 상태이며 위반은 `POST /csp-report` 로 수집된다.
- **Guard 서비스 토큰 운영 스크립트**: `scripts/set-guard-mode.sh`(OFF/WARN/ENFORCE 전환, 검증·자동 복구), `scripts/split-guard-tokens.sh`(공유 토큰 → 호출자별 토큰: `--check` → `--apply` → `--finalize`, 되돌리기 `--rollback`, 권한 부여 `--scopes`).
- **자격증명**: compose 는 `POSTGRES_PASSWORD` 가 없으면 기동을 거부한다. 교체 절차는 `scripts/rotate-credentials.sh`.

---

## 14. 변경 이력

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
