# Doro 인증·인가 연동 가이드 (에이전트용)

> **이 문서의 목적**: Doro IAM(인증) / Doro Guard(인가) / Doro SDK 를 사용하는 프로젝트에서 작업하는 AI 에이전트가, **`Doro/docs/USER_GUIDE.md` 나 README 를 믿지 않고도** 실제 동작에 맞게 연동하도록 돕는다.
>
> **왜 필요한가**: USER_GUIDE/README 는 실제 코드와 여러 곳에서 다르다(§9). 이 문서는 **소스를 직접 읽고** 검증한 사실만 적었다.

## 0. 출처와 신뢰 범위

| 항목 | 내용 |
|---|---|
| 조사일 | 2026-09-30 |
| 대상 | `/Users/yusm/Documents/workspace/Doro` (HEAD `84b8da7`, 2026-09-23) + **커밋되지 않은 수정본**(working tree). 조사한 것은 working tree다. |
| 참조 구현 | `/Users/yusm/Documents/workspace/doro-blog` (SDK 를 실제로 쓰는 서브 서비스) |
| 표기 | **[코드]** 소스에서 직접 확인 / **[미검증]** 추론이거나 실행해 보지 않음 |
| 미조사 | `doro-menu`, `server-secret`(시크릿이라 열지 않음), 배포 서버 실제 상태, 런타임 실행 |

**Doro 가 바뀌면 이 문서는 낡는다.** 코드를 수정하기 전에 §11 의 빠른 검증 명령으로 핵심 전제를 다시 확인할 것. 이 문서와 코드가 다르면 **코드가 맞다.**

---

## 1. 큰 그림

```
브라우저 ──▶ [Gateway nginx :443] ──▶ Portal(web) / IAM(auth) / 서브서비스
                                      │
서브서비스 ──(JWKS 조회, 로컬 서명검증)──▶ IAM  :8080  /.well-known/jwks.json
서브서비스 ──(gRPC :9090 / REST :8081)──▶ Guard
IAM ──(REST, 역할 튜플 동기화)──▶ Guard
```

| 구성요소 | 포트 | 역할 |
|---|---|---|
| IAM (`auth`) | 8080 | 가입/로그인/2FA/세션/토큰 발급, JWKS, 관리자 API |
| Guard (`guard`) | 8081 REST, 9090 gRPC | Zanzibar 방식 ReBAC. 튜플 저장 + `Check` |
| SDK (`sdk`) | (라이브러리) | JWT 로컬 검증 필터, `@DoroGuard`, `@CurrentDoroUser`, gRPC 클라이언트 |
| Portal (`web`) | 3000 | 통합 계정 센터 UI (React) |
| Gateway | 80/443 | nginx 리버스 프록시. 모든 서비스를 **같은 origin** 으로 묶음 |

- 서비스별 **독립 DB** 규칙: IAM=`doro_auth`, Guard=`doro_guard`, 서브서비스=`service_{name}`. [코드: `docker-compose.yml`, `AGENTS.md`]
- DB 는 공용 PostgreSQL 컨테이너 하나에 여러 DB. Redis 는 IAM/Guard 가 공유. [코드]

### 1.1 "통합 로그인"의 실체 (중요)

**진짜 SSO(리다이렉트 기반 OAuth)는 현재 동작하지 않는다.** 지금의 통합 로그인은 이렇다.

- 모든 프론트엔드가 **같은 IAM API 를 같은 origin(게이트웨이)으로 직접 호출**해 로그인하고, 액세스/리프레시 토큰을 **브라우저 localStorage** 에 저장한다. [코드: `doro-blog/web/src/store/authStore.ts`, `Doro/web/src/api/client.ts`]
- 서브서비스 백엔드는 `Authorization: Bearer <JWT>` 를 받아 JWKS 로 서명을 검증한다.
- 즉 "계정이 통합"되어 있지, "한 번 로그인하면 다른 서비스가 자동 로그인"되는 구조가 아니다. (같은 origin 의 localStorage 를 공유하는 효과가 있을 뿐)

OAuth 2.1 엔드포인트(`/oauth2/authorize`, `/oauth2/token`)는 존재하지만 **실사용이 불가능하다** (§5).

---

## 2. IAM API — 실제 규격

기본 URL `http://localhost:8080`. **JSON 필드는 camelCase.** (`spring.jackson` 네이밍 설정 없음 [코드])

### 2.1 응답 형식 — 두 가지가 섞여 있다

```jsonc
// 성공: ApiResponse 봉투
{ "success": true, "data": { ... }, "timestamp": "2026-09-30T03:00:00Z" }

// 실패: ErrorResponse (봉투 아님! success 필드 없음)
{ "timestamp": "...", "status": 401, "error": "Unauthorized",
  "code": "INVALID_CREDENTIALS", "message": "...", "path": "/api/v1/auth/login",
  "details": [ { "field": "email", "rejectedValue": "x", "reason": "..." } ] }
```

- 성공은 항상 `data` 를 꺼내 써야 한다. 실패는 `code` 는 **enum 이름**(`INVALID_CREDENTIALS`)이고, `AUTH_40102` 같은 숫자 코드는 응답에 안 나온다. 검증 실패만 `code: "INVALID_INPUT_VALUE"`. [코드: `GlobalExceptionHandler`, `ErrorCode`]
- 워크스페이스 룰(`{success:false, code, message, status}`)과는 **다르다**. 서브서비스는 자체 `ApiResponse.error` 를 쓴다(doro-blog 참고).

### 2.2 엔드포인트 (전부 `/api/v1/auth/**` 는 인증 없이 호출 가능)

| 메서드·경로 | 요청 | 응답 `data` | 비고 |
|---|---|---|---|
| `POST /api/v1/auth/signup` | `{email, password(8~64자), name(2~50자)}` | `{userId}` (201) | ⚠ 가이드의 `fullName` 은 **틀림**. `name` |
| `POST /api/v1/auth/login` | `{email, password, deviceInfo?}` | `{requires2fa, tempTicket?, tokens?}` | ⚠ `clientIp/userAgent` 는 서버가 요청에서 추출. `status:"SUCCESS"` 같은 필드 **없음** |
| `POST /api/v1/auth/2fa/login` | `{tempTicket, code(6자리), deviceInfo?}` | `TokenResponse` | 가이드의 `/login/2fa`·`twoFactorTicket`·`totpCode` 는 **틀림** |
| `POST /api/v1/auth/2fa/setup` | (Bearer) | `{secret, qrUri}` | ⚠ 호출 즉시 시크릿이 **저장·활성화**됨 (§9-D). 가이드의 `secretKey/otpAuthUri` 는 **틀림** |
| `POST /api/v1/auth/2fa/verify` | (Bearer) `{code}` | 없음 | 코드 검증만 함. "활성화 확정" 기능이 아님 |
| `POST /api/v1/auth/2fa/disable` | (Bearer) | 없음 | |
| `POST /api/v1/auth/token/refresh` | `{refreshToken}` | `TokenResponse` | RTR. 이전 토큰 즉시 폐기 |
| `POST /api/v1/auth/logout?sessionId=<uuid>` | 쿼리 파라미터 | 없음 | ⚠ 가이드의 Bearer+body 방식과 **다름**. 인증 검사 없음(§9-C) |
| `POST /api/v1/auth/lookup` | `{email}` | `{email,name,profileImageUrl}` | 계정 존재 여부 노출(열거 가능) |
| `GET /api/v1/users/me` | (Bearer) | 프로필 | |
| `PATCH /api/v1/users/me` | (Bearer) | 프로필 | |
| `PUT /api/v1/users/me/password` | (Bearer) | 없음 | |
| `GET /api/v1/sessions` | (Bearer) | 활성 세션 목록 | |
| `DELETE /api/v1/sessions/{id}` | (Bearer) | 없음 | ⚠ 소유자 검사 없음 (§9-C) |
| `POST /api/v1/sessions/revoke-others?currentSessionId=` | (Bearer) | 없음 | |
| `GET/PATCH/DELETE /api/v1/admin/users…` | (Bearer, ADMIN 이상) | | 역할 변경은 Guard `system:doro#manage_roles` 로 재검사 |
| `GET /.well-known/jwks.json` | 없음 | JWKS | 서브서비스가 사용 |

`TokenResponse` = `{accessToken, refreshToken, tokenType:"Bearer", expiresIn, sessionId, userIndex}`. [코드]

**존재하지 않는 엔드포인트 (가이드에만 있음)**: `/api/v1/auth/accounts/add`, `/accounts/switch/{uidx}`, `/2fa/enable`, `/login/2fa`. 다중 계정 "Google 스타일 전환"은 서버에 구현돼 있지 않다. `uidx` 는 사용자별 활성 세션 순번(max+1)일 뿐이다. [코드: `SessionService.createSession`]

### 2.3 로그인 보안 동작 [코드]

- 비밀번호 5회 연속 실패 → 15분 잠금 → **HTTP 403** `ACCOUNT_LOCKED` (가이드의 423 은 틀림).
- 2FA 티켓: 5분 유효, OTP 5회 실패 시 폐기. **인메모리 저장**이라 IAM 을 재시작하거나 여러 인스턴스면 깨진다.
- 같은 IP+User-Agent 의 이전 활성 세션은 새 로그인 시 자동 비활성화.
- 비밀번호는 Argon2id (`CustomArgon2PasswordEncoder`).

---

## 3. 토큰

| 항목 | 값 | 출처 |
|---|---|---|
| 알고리즘 | RS256, 헤더 `kid` (기본 `doro-iam-key-2026-v1`) | `JwtTokenProvider` |
| **액세스 토큰 TTL** | **86400초 (24시간)** ⚠ README 의 "15분(900)"은 틀림 | `application.yaml` |
| 리프레시 토큰 TTL | 2592000초 (30일), 불투명 랜덤값(SHA-256 해시로 DB 저장) | `RefreshTokenService` |
| 세션 무활동 만료 | 30일, 사용자당 최대 10개 설정값 | `application.yaml` |
| 클레임 | `sub`(userId UUID), `email`, `sid`(세션 UUID), `uidx`(int), `role`(`USER`/`ADMIN`/`SUPER_ADMIN`), `iss`, `iat`, `exp` | `JwtTokenProvider` |
| 키 보관 | 설정 PEM → 없으면 Redis(`doro:iam:jwt:keypair:*`, **평문**) → 없으면 신규 생성 후 Redis 저장 | `JwtKeyProvider` |
| `iss` 값 | `https://auth.doro.local` (설정값, 실제 도메인 아님) | `application.yaml` |

- JWKS 의 `n` 은 `BigInteger.toByteArray()` 를 그대로 인코딩해 **선행 0x00 바이트**가 붙을 수 있다. SDK 는 `new BigInteger(1, bytes)` 라 문제없지만, **다른 JWT 라이브러리는 실패할 수 있다**. [코드, 미검증(타 라이브러리)]
- **로그아웃해도 서브서비스는 최대 24시간 토큰을 계속 인정한다.** §9-A.

---

## 4. Doro SDK — 실제 동작

**의존성**: `implementation 'com.hunnit-beasts:doro-sdk'` + `settings.gradle` 에서
```groovy
includeBuild('../Doro') {
    dependencySubstitution { substitute module('com.hunnit-beasts:doro-sdk') using project(':sdk') }
}
```
(doro-blog 방식. 아티팩트 저장소에 배포된 적 없음 [코드: 참조 프로젝트에 다른 방식 없음].)

**전제 조건**
- **Java 25 툴체인 필수** (SDK 가 25 로 컴파일). 서브서비스가 Java 21 이면 사용 불가. [코드: `sdk/build.gradle`]
- Spring Boot 4.1.x (`starter-web`, doro-blog 은 4.1.0), `spring-aspects`.
- **Spring Security 와 무관하게 동작**한다. SDK 는 Spring Security 컨텍스트를 채우지 않는다. doro-blog 은 Security 스타터를 쓰지 않는다. 함께 쓰면 두 인증 체계가 따로 논다.

### 4.1 `DoroJwtAuthFilter` [코드]
- 서블릿 필터, `FilterRegistrationBean`, **order = `HIGHEST_PRECEDENCE`**. 자동설정에 `@ConditionalOnMissingBean`.
- **`Authorization: Bearer` 헤더만** 읽는다. **쿠키는 읽지 않는다.**
- 토큰이 없거나 **검증에 실패해도 요청을 막지 않는다**(경고 로그만). 사용자만 `anonymous` 로 남는다. → **인증 강제는 SDK 가 해주지 않는다.** 컨트롤러가 직접 `isAuthenticated()` 를 확인하거나 `@DoroGuard` 를 써야 한다.
- 서명·만료만 검증한다. **`iss`, `aud` 는 검증하지 않는다.** `DoroProperties.iam.issuer` 는 있지만 사용되지 않는다.
- Redis 킬스위치(세션 블랙리스트)를 **확인하지 않는다.**
- 사용자 정보는 `ThreadLocal`(`DoroUserContext`)에 담긴다. 비동기 스레드로 넘어가면 사라진다.
- `X-Trace-Id` 를 MDC 에 넣는다.

### 4.2 `@CurrentDoroUser` [코드]
- 타입 `DoroUser`(record: `userId, email, sessionId, userIndex, role`), `UUID`, `String` 지원.
- **비로그인이면 예외가 아니라 `anonymous`**(`userId == null`, `role == "ANON"`)가 주입된다. `UUID`/`String` 으로 받으면 **`null`**. NPE 주의.
- `DoroUser.isAuthenticated()`, `isAdmin()`, `isSuperAdmin()` 제공. `role` 은 **JWT 클레임 값**이다(Guard 를 거치지 않음).

### 4.3 `@DoroGuard` [코드]
```java
@DoroGuard("board_post:#postId#viewer")                       // 단축형: namespace:objectSpEL#relation
@DoroGuard(namespace="x", object="#id", relation="editor")    // 속성형
@DoroGuard(namespace="x", object="#id", relation="viewer", subject="#targetUserId") // 대리 검사
```
- SpEL 변수: 메서드 **파라미터 이름**(`-parameters` 컴파일 옵션 필수 — doro-blog 은 설정함), `#p0/#a0`, `#args`.
- `#` 로 시작하지 않는 표현식은 리터럴로 취급.
- **비로그인 + `subject` 미지정 → `DoroAccessDeniedException`**. SDK 는 이걸 HTTP 로 변환해 주지 **않는다.** 서비스가 `@ExceptionHandler(DoroAccessDeniedException.class)` 로 **403** 을 직접 만들어야 한다(없으면 500). doro-blog `GlobalExceptionHandler` 참고. USER_GUIDE 의 "403 자동 차단"은 부정확.
- 401 과 403 을 구분하지 않는다. 비로그인도 403 이 된다.
- AOP 기반이라 **같은 클래스 내부 호출(self-invocation)** 에는 적용되지 않고, **`public` 메서드·Spring 빈**이어야 한다. [미검증(일반 Spring AOP 특성)]

### 4.4 `DoroGuardClient` [코드]
- gRPC(plaintext, `usePlaintext()`), 호출당 **3초 deadline**.
- `check` — 예외 시 **`false`(fail-closed)**.
- `writeTuple` / `deleteTuple` — 예외 시 **삼키고 `0` 반환**. **반환값을 확인하지 않으면 DB 와 Guard 튜플이 조용히 어긋난다.** 호출 결과가 `0` 이면 실패로 처리하거나 재시도/보상 로직을 둘 것.
- 튜플 쓰기와 서비스 DB 저장은 **하나의 트랜잭션이 아니다.** (doro-blog 도 동일 — 저장 후 `writeTuple`)

### 4.5 설정 (`application.yaml`) [코드: `DoroProperties`]
```yaml
doro:
  iam:
    jwks-uri: http://localhost:8080/.well-known/jwks.json   # 실사용
    issuer: https://auth.doro.local                          # 선언만 있고 검증 안 함
  guard:
    grpc-host: localhost
    grpc-port: 9090
    enabled: true
```
- doro-blog 은 추가로 `doro.guard.http-url` 을 자체 사용(스키마 등록용, SDK 속성 아님).
- JWKS 는 `kid` 캐시 미스일 때만 가져온다. IAM 이 **키를 재생성**(Redis 가 비었을 때 등)하면 새 `kid` 가 아니라 **같은 `kid` 에 다른 키**가 되어, SDK 캐시가 낡은 키를 계속 써서 **모든 토큰 검증이 실패**할 수 있다. [코드에서 추론, 미검증]

---

## 5. OAuth 2.1 / OIDC — 현재는 쓸 수 없다

`/oauth2/authorize`, `/oauth2/token`, `/.well-known/openid-configuration` 이 있지만 실제로는 다음과 같다. [코드: `OAuth2Controller`, `OAuth2Service`]

| 사실 | 영향 |
|---|---|
| `GET /oauth2/authorize` 는 **302 리다이렉트가 아니라 JSON** `{data:{code,state}}` 을 반환한다 | 브라우저 리다이렉트 플로우 불가. SPA 가 호출해서 직접 이동시켜야 함 |
| 호출에 **로그인된 Bearer 토큰이 필요**하다. 없으면 `userId=null` 로 코드가 발급되고 이후 교환에서 500 | 미로그인 상태의 authorize 는 깨짐 |
| **`client_id` 등록소가 없다.** 아무 값이나 통과. **`redirect_uri` 허용목록도 없다.** | 오픈 리다이렉트/코드 탈취 가능. PKCE 가 일부 완화 |
| 인가 코드 저장소가 **인메모리** `ConcurrentHashMap` | 재시작/다중 인스턴스에서 코드 유실 |
| `POST /oauth2/token` 은 **`application/json` 만** 받는다. 필드는 camelCase `grantType, code, redirectUri, clientId, codeVerifier` | 가이드의 `x-www-form-urlencoded` + snake_case 는 **틀림** |
| `refresh_token` grant 는 discovery 에 광고되지만 **토큰 엔드포인트가 지원하지 않는다**(`authorization_code` 만) | 갱신은 `/api/v1/auth/token/refresh` 로 |
| `scope`, `code_challenge_method` 는 받지만 무시. PKCE 는 S256 고정 | |
| 웹 포털 `OAuthConsentPage` 는 **시뮬레이션**이다. 가짜 코드 `doro_auth_code_sample_123` 으로 이동할 뿐 `/authorize` 를 호출하지 않는다 | UI 도 없음 |

**결론**: 새 서비스가 "OAuth 로 붙는다"는 설계를 하지 말 것. 진짜 SSO 가 필요하면 **Doro 쪽 개발이 선행**돼야 한다(§8-B).

---

## 6. Guard — 실제 규격

### 6.1 REST (`:8081`) — ⚠ **인증이 전혀 없다**
경로는 `/api/v1/guard/**` 이다. (USER_GUIDE 의 `/api/v1/tuples`, `/api/v1/schemas`, `/api/v1/tuples/check` 는 **틀림**. IAM 자신도 `/api/v1/guard/...` 를 호출한다.) 응답은 `{success, data, ...}` 봉투.

| 메서드·경로 | 요청 | 응답 `data` |
|---|---|---|
| `POST /api/v1/guard/check` | `{namespace, objectId, relation, subjectNamespace, subjectId, subjectRelation?}` | `{allowed, depth, reason}` |
| `POST /api/v1/guard/tuples` | `[ {namespace, objectId, relation, subjectNamespace, subjectId, subjectRelation?} ]` | `{writtenCount}` |
| `DELETE /api/v1/guard/tuples` | 위와 동일한 배열(본문 있는 DELETE) | `{deletedCount}` |
| `GET /api/v1/guard/schema` | 없음 | **DSL 문자열** (`data` 가 곧 텍스트) |
| `POST /api/v1/guard/schema` | **JSON** `{"dsl": "..."}` (`text/plain` 아님) | `{version, dsl, active}` |

- **Guard 에는 Spring Security 가 없다.** 8081/9090 에 도달할 수 있으면 누구나 튜플을 쓰고 스키마를 바꿀 수 있다. **절대 외부 노출 금지.** 게이트웨이 nginx 는 Guard 를 프록시하지 않는다(확인함). docker-compose 는 8081/9090 을 호스트에 **게시**하므로 방화벽/바인딩 주소를 확인할 것.
- gRPC 는 `doro.guard.v1.GuardService` : `Check`, `WriteTuples`, `DeleteTuples`, `Expand`. `subject_relation` 필드가 있다. [코드: `doro_guard.proto`]

### 6.2 스키마는 **전역 단일 버전**이고 등록은 **전체 교체**다 🔴
[코드: `SchemaService.registerSchema`]

- 활성 스키마는 Guard 전체에 **하나**다. `POST /schema` 는 보낸 DSL 로 **통째로 교체**한다. 병합하지 않는다.
- 따라서 **자기 서비스 타입만 POST 하면 다른 서비스와 IAM 의 스키마가 사라진다.** `system` 타입이 사라지면 IAM 의 관리자 인가(`manage_roles`, `can_reset_2fa`)가 전부 거부된다.
- USER_GUIDE §4 의 "curl 로 board 타입만 POST" 절차는 **위험하다.**
- **올바른 절차 (doro-blog `BlogSchemaInitializer` 방식)**:
  1. `GET /api/v1/guard/schema` 로 활성 DSL 을 받는다.
  2. 자기 타입이 이미 있는지 확인한다(`contains("type blog_post")` 식).
  3. 없으면 `기존 DSL + "\n\n" + 내 DSL` 을 `POST {"dsl": ...}` 한다.
- 남은 위험: (a) **읽기-수정-쓰기 경합** — 두 서비스가 동시에 부팅하면 한쪽 변경이 유실된다. (b) 스키마 변경 시 **L1 캐시를 무효화하지 않는다**(튜플 변경 때만 무효화). 결과가 최대 60초 낡을 수 있다. (c) 활성 스키마는 **요청을 받은 인스턴스 메모리**에만 반영된다. Guard 를 다중 인스턴스로 띄우면 나머지는 재시작 전까지 낡은 스키마를 쓴다.
- 워크스페이스 룰은 스키마 변경을 "정규 버전 관리"로 하라고 하므로, 새 타입의 `.doro` 파일을 서비스 저장소에 두고(doro-blog 의 `blog-schema.doro`) 위 절차로 등록한다.
- **네임스페이스는 서비스 접두사를 붙일 것**(`blog_post`, `sebi_bill`). 기본 스키마가 이미 `user`, `group`, `system`, `folder`, `document` 를 쓴다.

### 6.3 DSL 문법 — 이 파서가 실제로 받아들이는 것
[코드: `DslParser`, `CheckEngine`] **줄 단위 파서**다. 표준 Zanzibar DSL 이나 USER_GUIDE 예제를 그대로 쓰면 깨진다.

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
3. **괄호 `( )` 미지원.** USER_GUIDE 의 `(member & nda_signed) - blocked` 는 **깨진다**(`"(member"` 라는 릴레이션으로 해석됨).
4. **`&` 와 `|` 를 한 식에 섞지 마라.** 우선순위 처리가 없다(`-` → `&` → `|` 순으로 문자열을 자른다). `a & b | c` 는 의도와 다르게 파싱된다. `-` 는 왼쪽에 `|`/`&` 를 둘 수 있으나 오른쪽은 단일 항만 신뢰할 것.
5. **릴레이션을 사용하기 전에 선언하라.** `editor` 가 `viewer` 보다 **위에** 있어야 `viewer: editor | ...` 의 `editor` 가 "같은 객체의 다른 릴레이션"으로 해석된다. 뒤에 선언하면 무의미한 직접 항으로 취급돼 **항상 false**.
6. `x#y` 형태의 항은 항상 **TTU** 로 해석된다: "현재 객체의 `x` 릴레이션 튜플이 가리키는 객체에서 `y` 를 검사".

**이 엔진의 특이한 점 — 반드시 알 것**
- **직접 튜플은 스키마를 거치지 않고 매칭된다.** `Check` 는 먼저 튜플 테이블에서 `(namespace, objectId, relation, subject)` 가 정확히 있는지 본다. **스키마에 없는 타입/릴레이션이라도 튜플이 있으면 `true`** 다. 쓰기 시에도 타입·릴레이션 검증이 없다. 오타가 나도 조용히 저장된다.
- `relation author: user` 같은 **타입 제약은 강제되지 않는다**(`DirectRelationNode` 는 평가 시 항상 false, 직접 튜플은 위 규칙으로 처리). `viewer: author | user` 의 `user` 는 "모든 사용자"가 **아니다**(무의미한 항).
- 그룹(userset) 멤버십은 **튜플 쪽**에서 처리한다: `doc:1#viewer@group:eng#member` 같은 튜플(`subjectRelation="member"`)을 쓰면 엔진이 그룹 멤버를 재귀 검사한다. 스키마의 `group#member` 항은 이 동작과 무관하다.
- 최대 깊이 32, 순환 방어(방문 집합). 초과·순환은 `false`.
- 결과 캐시: Caffeine L1, **60초 TTL, 최대 5만 건, 허용/거부 모두 캐시**. 튜플 쓰기/삭제 시 `invalidateAll`. **Redis L2 캐시는 없다**(README 의 "L1/L2"는 부정확).
- 튜플 쓰기는 **멱등**(이미 있으면 건너뜀, `writtenCount` 에 안 잡힘).

### 6.4 기본 스키마 (`guard/src/main/resources/schema.doro`)
`user`(manager, super_manager, can_reset_2fa), `group`, `system`(super_admin, admin, auditor, manage_roles), `folder`, `document`. IAM 이 사용자 가입/역할 변경 때 `system:doro#admin|super_admin` 과 `user:<id>#manager|super_manager` 튜플을 쓴다. [코드: `UserRelationSyncService`]

### 6.5 역할과 인가의 이중 구조
- **JWT `role` 클레임**(`USER/ADMIN/SUPER_ADMIN`)은 IAM DB 의 `users.role` 에서 나오고, IAM 의 `hasAnyRole` 같은 정적 검사에 쓰인다.
- **Guard 튜플**은 IAM 이 역할 변경 시 동기화한다.
- 워크스페이스 룰은 "역할 분기문 금지, Guard 에 위임"이다. **관리자 판정은 `guardClient.check("system","doro","admin",userId)` 로 하고 `DoroUser.isAdmin()` 에 의존하지 말 것.** 클레임은 최대 24시간 낡을 수 있다(역할 강등 후에도).

---

## 7. 응답·에러·로깅 규약 (참조 구현 doro-blog 기준)

- 서브서비스 `ApiResponse<T>` 성공/실패 봉투를 **자체 정의**한다(IAM 과 별개). 실패는 `GlobalExceptionHandler` 로 통일.
- 반드시 매핑할 예외: `DoroAccessDeniedException → 403`, `HttpRequestMethodNotSupportedException → 405`, `HttpMessageNotReadableException → 400`. [코드: `doro-blog/AGENTS.md`, `GlobalExceptionHandler`]
- 로그는 **stdout + SLF4J**. `X-Trace-Id` 는 SDK 필터가 MDC 에 넣으므로 서비스 간 호출에 헤더로 전파. 토큰·세션 ID·TOTP 시크릿을 로그에 남기지 말 것.
- DB: **Flyway + `ddl-auto: validate`**. `hibernate.ddl-auto: update` 금지. Flyway 히스토리 테이블은 `{name}_schema_history`. [코드: 워크스페이스/Doro AGENTS.md]

---

## 8. 레시피

### A. 새 서브 서비스(REST 백엔드) 추가 — 체크리스트
`Doro/AGENTS.md` §5 의 5단계를 **아래 보정**과 함께 수행한다.

1. **DB**: `service_{name}` 을 `docker-compose.yml` 의 `POSTGRES_MULTIPLE_DATABASES`, `.env.example`, `.env` 에 추가. Flyway `{name}_schema_history`, `ddl-auto: validate`.
2. **SDK**: Java **25**, `includeBuild('../Doro')` + `dependencySubstitution`, `-parameters` 컴파일 옵션, `doro.iam.jwks-uri`/`doro.guard.*` 설정. **Spring Security 스타터는 넣지 않는다.**
3. **스키마**: 서비스 접두사 네임스페이스로 `.doro` 작성 → **§6.2 절차(GET → 병합 → POST)** 로 등록. **절대 자기 타입만 POST 하지 말 것.** 등록 직전에 기존 DSL 을 백업(`GET` 결과를 파일로 저장)해 두면 복구가 쉽다.
4. **컨트롤러**: 조회는 공개 가능. 변경은 `@DoroGuard` 또는 `@CurrentDoroUser` + `isAuthenticated()` 확인. **`@CurrentDoroUser UUID` 는 비로그인 시 null.** `DoroAccessDeniedException → 403` 핸들러 필수.
5. **튜플 동기화**: 리소스 생성 시 `writeTuple`, 삭제 시 `deleteTuple`. **반환값 0 을 실패로 취급**하거나 로그+재시도. 리소스 저장 후 튜플 쓰기 실패 시 고아 상태가 될 수 있음을 설계에 반영.
6. **게이트웨이**: `Doro/gateway/nginx.conf` 에 `upstream` + `location` 추가(라우팅 우선순위 주의 — `doro-blog/AGENTS.md`). Guard 를 프록시하지 말 것.
7. **검증**: 빌드, 컨테이너 기동 `healthy`, 실제 엔드포인트 호출(워크스페이스 룰 §5). 아래 §11 명령으로 인증/인가를 **직접 확인**.

### B. 서버 렌더링(Thymeleaf 등) 웹앱이 Doro 로 로그인하기

**현재 가능한 방식 — "인앱 로그인 + HttpOnly 쿠키" (Doro 수정 불필요)**

1. 웹앱이 **자체 로그인 폼**을 렌더링한다(doro-blog 이 하는 "인앱 로그인"과 같은 철학 — `doro-blog/AGENTS.md` §3-3).
2. 서버가 **서버 간 호출**로 `POST {IAM}/api/v1/auth/login` 을 한다. 응답 `data.requires2fa` 가 true 면 `tempTicket` 을 세션에 잠깐 두고 OTP 폼 → `POST /api/v1/auth/2fa/login`.
3. 받은 `accessToken`/`refreshToken` 을 **`HttpOnly; Secure; SameSite=Lax` 쿠키**로 내려준다. (localStorage/JS 노출 금지)
4. 이후 요청마다 **쿠키의 액세스 토큰을 `Authorization: Bearer` 로 바꿔서 SDK 필터에 전달**한다.
   - ⚠ SDK 필터는 **`HIGHEST_PRECEDENCE`** 로 등록되어, 별도 필터를 그 앞에 세울 수 없다.
   - **해결안 [미검증 — 반드시 테스트로 확인]**: 웹앱이 `FilterRegistrationBean<DoroJwtAuthFilter>` 타입의 빈을 **직접 정의**해 SDK 의 자동설정을 물러나게 한다(`@ConditionalOnMissingBean`). 그 빈에 등록하는 필터는 `HttpServletRequestWrapper` 로 `Authorization` 헤더를 채운 뒤 `new DoroJwtAuthFilter(jwksKeyProvider)` 에 위임하는 합성 필터로 만든다.
5. 만료 처리: 액세스 TTL 이 24시간이라 드물지만, 만료 시 `POST /api/v1/auth/token/refresh` 로 갱신하고 **쿠키를 교체**(RTR 이므로 이전 리프레시 토큰은 즉시 무효 — 동시 요청 경합 주의, 재사용 감지 시 세션 전체가 종료됨).
6. 로그아웃: `POST /api/v1/auth/logout?sessionId=` 후 쿠키 삭제. **그래도 발급된 액세스 토큰은 만료 전까지 유효**(§9-A).
7. **CSRF**: 쿠키 인증이므로 상태 변경 요청에 CSRF 토큰이 필요하다(Spring Security 를 쓰지 않으니 직접 구현하거나 `SameSite=Strict` + Origin 검사).
8. 웹앱이 **비밀번호를 직접 다루게 된다**는 한계가 있다. 이는 진짜 SSO 가 아니다.

**진짜 SSO(리다이렉트 방식)에 필요한 Doro 쪽 선행 작업 — 지금은 없음**
- OAuth 클라이언트 등록 테이블 + `redirect_uri` 허용목록
- 미로그인 시 Doro 로그인 화면으로 보내고, 성공 후 **실제로** `/oauth2/authorize` 를 호출해 리다이렉트하는 동의 화면(현재는 시뮬레이션)
- 토큰 엔드포인트 form-urlencoded 지원 및 인가 코드 저장소를 Redis 로
- 이 작업은 Doro 저장소 변경이므로 **사용자 승인 후** 진행할 것.

### C. 인가 설계 원칙
- **본인 데이터**(내 구독, 내 알림)는 서비스 DB 의 `user_id = :currentUser` 조건으로 충분. 굳이 튜플로 만들지 않는다.
- **관리자 기능**은 `guardClient.check("system","doro","admin", userId)`. 클레임(`role`)에 의존하지 않는다.
- **공유/계층 리소스**(글, 시리즈, 채널)는 튜플 + `@DoroGuard`.
- 튜플 키는 **문자열 ID** (UUID `.toString()`). 사용자 subject 는 `subjectNamespace="user"`, `subjectId=<UUID>`.

---

## 9. 알려진 결함과 가이드 불일치 (위험도순)

에이전트는 **이 결함들을 "의도된 동작"으로 가정하거나 우회 코드로 덮지 말고**, 필요하면 사용자에게 보고한다. 수정은 Doro 저장소 변경이므로 사용자 승인이 필요하다.

### 🔴 높음
| ID | 내용 | 근거 |
|---|---|---|
| **A** | **로그아웃/세션 종료가 서브서비스에 반영되지 않는다.** IAM 은 Redis 킬스위치를 발행하지만 SDK 필터는 확인하지 않고, 액세스 토큰 TTL 은 24시간이다. README 의 "모든 서브 서비스에서 즉시 차단"은 사실이 아니다. 탈취된 토큰은 최대 24시간 유효. | `DoroJwtAuthFilter`(Redis 참조 없음), `application.yaml` |
| **B** | **Guard REST/gRPC 무인증.** 접근 가능하면 스키마 전체 교체·튜플 위조로 권한 상승 가능. | guard `build.gradle` 에 security 없음, `TupleController`/`SchemaController` |
| **B2** | **스키마 등록이 전체 교체 + 캐시 미무효화 + 단일 인스턴스 반영.** USER_GUIDE 절차를 따르면 IAM 관리자 인가가 깨진다. | `SchemaService` (§6.2) |
| **B3** | OAuth `client_id`/`redirect_uri` 검증 없음, 인가 코드·2FA 티켓 인메모리. | `OAuth2Service`, `AuthService` |

### 🟡 중간
| ID | 내용 |
|---|---|
| **C** | `POST /auth/logout` 은 **인증 없이** `sessionId` 만으로 세션을 종료한다. `DELETE /sessions/{id}` 는 로그인만 하면 **남의 세션도** 종료할 수 있고(소유자 검사 없음), 킬스위치·리프레시 토큰 폐기도 하지 않는다. |
| **D** | `POST /2fa/setup` 이 시크릿을 **즉시 저장**한다. 사용자가 검증 없이 설정을 중단하면 다음 로그인부터 `2FA_REQUIRED` 인데 시크릿을 모르는 상태 → **계정 잠김**. 관리자 `DELETE /admin/users/{id}/2fa` 로만 복구. |
| **E** | SDK 가 `iss`/`aud` 를 검증하지 않는다. 같은 JWKS 로 서명된 다른 용도의 토큰이 통과한다. |
| **F** | `writeTuple/deleteTuple` 실패가 조용히 `0` 반환(§4.4). |
| **G** | IAM CORS 가 `allowedOriginPatterns("*")` + `allowCredentials(true)`. 워크스페이스 룰(명시적 Origin) 위반. |
| **H** | RSA 개인키가 Redis 에 평문 저장. Redis 에 접근 가능하면 토큰 위조 가능. |
| **I** | 스키마/튜플이 타입 검증 없이 저장됨(§6.3). 오타·잘못된 릴레이션이 조용히 통과. |
| **J** | JWKS `n` 선행 0x00. 타 라이브러리 호환성. |

### 📋 USER_GUIDE / README ↔ 실제 코드 불일치 요약
| 문서 주장 | 실제 |
|---|---|
| 액세스 토큰 15분 | **24시간** |
| 가입 필드 `fullName` | `name` |
| 로그인 응답 `status: SUCCESS/2FA_REQUIRED`, `twoFactorTicket` | `{requires2fa, tempTicket, tokens}` |
| 2FA 설정 응답 `secretKey`, `otpAuthUri` | `secret`, `qrUri` |
| `/2fa/enable`, `/login/2fa`, `totpCode` | `/2fa/verify`, `/2fa/login`, `code` |
| 로그아웃: Bearer + body(refreshToken) | `POST /logout?sessionId=` |
| 계정 잠금 423 | 403 |
| `/accounts/add`, `/accounts/switch/{uidx}` | **존재하지 않음** |
| `/oauth2/token` form-urlencoded, snake_case | **JSON 만**, camelCase |
| OAuth `refresh_token` grant | 토큰 엔드포인트 미지원 |
| Guard `/api/v1/tuples`, `/tuples/check`, `/schemas` | `/api/v1/guard/tuples`, `/guard/check`, `/guard/schema` |
| 스키마 등록 `text/plain`, 자기 타입만 | **JSON `{dsl}`**, 전체 교체 |
| DSL 괄호 `(a & b) - c` | 미지원(깨짐) |
| 403 자동 차단 | 서비스가 예외 핸들러를 직접 구현해야 함 |
| L1/L2 캐시 | L1 만 |
| Guard DB `doro_guard`(compose) / 기본 설정 `doro_iam`(application.yaml) | compose 는 `doro_guard`, 로컬 기본값은 `doro_iam` — 로컬 실행 시 IAM 과 **같은 DB** 를 쓰게 됨 |
| Java 25 / Boot 4 | 일치. 단 서브서비스도 Java 25 필요 |

---

## 10. 하지 말아야 할 것 (에이전트 체크리스트)

- ❌ USER_GUIDE/README 의 엔드포인트·필드를 그대로 복사해 코드를 쓰지 말 것 — §9 표로 대조.
- ❌ `POST /api/v1/guard/schema` 에 자기 타입만 보내지 말 것.
- ❌ Guard 포트(8081/9090)를 게이트웨이나 외부에 노출하지 말 것.
- ❌ `DoroUser.role` 이나 클레임으로 관리자 권한을 판정하지 말 것(Guard 에 위임).
- ❌ `@CurrentDoroUser UUID` 를 null 검사 없이 쓰지 말 것.
- ❌ `writeTuple` 반환값을 무시하지 말 것.
- ❌ 401/403/CORS 가 나온다고 `permitAll()`/`*` 로 개방하지 말 것(워크스페이스 룰). 원인을 찾을 것.
- ❌ 토큰·세션 ID·시크릿·`.env`·`server-secret` 내용을 로그·응답·문서·채팅에 남기지 말 것.
- ❌ Doro 저장소를 수정하는 작업(클라이언트 등록, 킬스위치 연동 등)을 사용자 승인 없이 하지 말 것.
- ❌ 이 문서를 근거로 "동작한다"고 사용자에게 보고하지 말 것. 워크스페이스 룰 §5: **직접 빌드하고 실행해 확인**한 뒤에만 보고.

---

## 11. 빠른 검증 명령 (전제 재확인용)

IAM/Guard 가 로컬에서 떠 있다고 가정(`docker compose up -d` in `Doro/`).

```bash
# 1) 토큰 TTL 과 실제 응답 형태 확인 (가입 → 로그인). 테스트 계정은 직접 만든 값 사용.
curl -s -X POST localhost:8080/api/v1/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"probe@example.test","password":"ProbePass123!","name":"probe"}'
curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"probe@example.test","password":"ProbePass123!"}'   # data.tokens.expiresIn == 86400 ?

# 2) JWKS
curl -s localhost:8080/.well-known/jwks.json

# 3) Guard 활성 스키마 (변경 전 항상 백업)
curl -s localhost:8081/api/v1/guard/schema > schema.backup.json

# 4) Guard 무인증 확인 (외부에서 열려 있으면 보고할 것)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8081/api/v1/guard/check \
  -H 'Content-Type: application/json' \
  -d '{"namespace":"system","objectId":"doro","relation":"admin","subjectNamespace":"user","subjectId":"00000000-0000-0000-0000-000000000000"}'

# 5) 킬스위치 미반영 재현: 로그인 → 토큰 저장 → logout?sessionId= → 같은 토큰으로 서브서비스 호출이 계속 200 인지
```

- 테스트 데이터는 반드시 **로컬/테스트 환경**에서만. 운영(`varen05…`) 서버에 호출하지 말 것.
- 이 문서의 `[미검증]` 항목은 위와 같은 실험이나 테스트로 확인한 뒤, 결과를 이 문서에 반영한다.

---

## 12. 소스 위치 색인 (Doro 저장소 기준)

| 주제 | 파일 |
|---|---|
| 로그인/2FA/토큰 | `auth/.../domain/auth/service/AuthService.java` |
| 토큰 발급·JWKS | `auth/.../core/token/JwtTokenProvider.java`, `JwtKeyProvider.java` |
| 리프레시 회전 | `auth/.../core/token/RefreshTokenService.java` |
| 세션 | `auth/.../domain/session/service/SessionService.java` |
| 보안 설정·CORS | `auth/.../config/SecurityConfig.java`, `CorsConfig.java` |
| OAuth | `auth/.../domain/oauth/service/OAuth2Service.java`, `interfaces/api/OAuth2Controller.java` |
| IAM→Guard 동기화 | `auth/.../domain/user/service/UserRelationSyncService.java`, `infrastructure/guard/GuardClient.java` |
| 에러 코드 | `auth/.../common/exception/ErrorCode.java`, `GlobalExceptionHandler.java` |
| Guard 엔진 | `guard/.../core/engine/CheckEngine.java` |
| DSL 파서 | `guard/.../core/dsl/parser/DslParser.java` |
| 스키마 등록 | `guard/.../core/dsl/service/SchemaService.java` |
| Guard REST | `guard/.../interfaces/api/{Check,Tuple,Schema}Controller.java` |
| 기본 스키마 | `guard/src/main/resources/schema.doro` |
| gRPC 계약 | `sdk/src/main/proto/doro_guard.proto` (guard 에도 사본 있음) |
| SDK 필터·AOP·클라이언트 | `sdk/.../security/filter/DoroJwtAuthFilter.java`, `aop/DoroGuardAspect.java`, `client/DoroGuardClient.java` |
| 참조 서비스 | `doro-blog/` (`BlogSchemaInitializer`, `blog-schema.doro`, `GlobalExceptionHandler`, `PostService`) |
| 라우팅 규칙 | `Doro/gateway/nginx.conf`, `doro-blog/AGENTS.md` |
| 엔지니어링 룰 | `workspace/AGENTS.md`, `Doro/AGENTS.md` |

---

## 부록. `hardening/phase1` 브랜치 진행 현황 (2026-09-30 검증 후 반영)

> 아래는 브랜치에 **커밋만 되어 있고 아직 배포되지 않았다**. 이 문서 본문(특히 DORO_AGENT_GUIDE 의 §2·§4·§6·§9)은 배포 전 코드(main) 기준이다. 병합·배포되면 본문을 갱신할 것.

| 영역 | 반영된 내용 | 상태 |
|---|---|---|
| Phase 1 인프라 | 내부 포트 127.0.0.1 바인딩, Redis `requirepass`(선택), DB 비밀번호 필수화, `/loki/` 쓰기 차단, `web/Dockerfile` 빌드 단계 복구, CI 실패 시 exit 1·`.env` 없으면 중단 | 코드 반영, 서버 미적용 |
| Phase 2 auth | C-1·C-2(소유자 검증 + 인증 필수, `SessionRevocationService`), N1(authorize 인증·형식 검증), N3(원자적 회전, 선택적 유예), N5(원자적 실패 카운트), N6(OTP 실패 합산·±1 스텝·재사용 방지), N7(비밀번호 변경 시 세션 종료), N11(민감값 마스킹·400), N13(만료 세션 정리), actuator 제한 | 반영·테스트 통과 |
| Phase 3 SDK | S1·S2·S3·S4·S6(`exp` 필수, RS256, `iss` 선택 검증)·S7, (c) 쿠키 토큰 소스(`doro.iam.cookie-name`), (d) 기본 예외 핸들러(401/403/503), S8/S10 `…OrThrow` | 반영·테스트 통과 |
| Phase 4 Guard | G2(잘린 평가는 캐시 안 함·차집합 fail-closed), G3(커밋 후 무효화·세대 검사·스키마 변경 시 무효화), G5(배치 중복 제거·`ON CONFLICT`·V2 정리·PG 부분 유니크 V3), G9·G10(예외·gRPC Status 매핑), G1(서비스 토큰 OFF/WARN/ENFORCE, 기본 OFF) | 반영·테스트 통과, V2/V3 는 PostgreSQL 에서 별도 검증 |

**아직 하지 않은 것 (결정 필요)**: 액세스 토큰 TTL 단축과 킬스위치 SDK 연동(Phase 5), 로그 뷰어 보호(`/loki/` 읽기), Guard 서비스 토큰 ENFORCE 전환, 스키마 서비스 단위 등록 API(G-a), OAuth/SSO(Phase 6), `/auth/**` 전체 `permitAll` 정리와 `2fa/disable` 재인증, 이메일 소문자 정규화(기존 중복 데이터 확인 필요), CORS Origin 목록, Flyway `repair()` 제거.

**배포 순서 권장**: (1) 서버 `.env` 에 `REDIS_PASSWORD`·`GRAFANA_ADMIN_PASSWORD` 추가 → (2) 브랜치 병합·배포(Guard V2 가 중복 튜플을 `relation_tuples_duplicates_backup` 로 옮기고 정리함) → (3) 모든 호출자에 `DORO_GUARD_SERVICE_TOKEN` 배포 후 Guard 를 `WARN` → 로그 확인 → `ENFORCE`.
