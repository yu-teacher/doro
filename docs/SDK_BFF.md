# SDK BFF 로그인과 탈퇴 동기화

> **참고**: BFF 는 이제 서비스 밖의 독립 서비스(`doro-bff`)로도 돌 수 있다(서비스 설정 `*.auth.mode=gateway`, 구조와 전환은 [`BFF.md`](BFF.md)). 이 문서는 **서비스 안에서 도는(내장) 모드**와, 두 모드가 함께 쓰는 SDK 부품(탈퇴 동기화 모듈 등)을 설명한다.

blog, party, games 가 각자 복사해 쓰던 "Doro OAuth 로그인(BFF)" 코드를 SDK(`doro-sdk`)로 올린 모듈이다.
서비스는 설정 값, 저장소(DB) 구현, 로그인 직후의 사용자 생성만 제공한다. 세션·로그인 CSRF·토큰 갱신·로그아웃·탈퇴 동기화 로직은 SDK 한 곳에 있다.

## 왜 한 곳에 두는가

세 저장소의 BFF 코드가 서로 갈라지면서 한 곳에서 고친 보안 수정이 다른 곳에 반영되지 않았다(party/games 는 IAM 장애 때 세션을 지우고, 로그인 CSRF 방어가 없고, 토큰 덮어쓰기 경쟁이 있었다).
이제 수정은 SDK 에 한 번만 들어가고, 서비스는 버전을 올려 받는다.

## BFF 로그인 (`com.hunnit_beasts.doro.sdk.bff`)

브라우저는 `/api/v1/bff/login` 으로 이동해 Doro 에서 로그인하고 `/callback` 에서 세션 쿠키를 받는다. 토큰은 서버 DB 에 AES-GCM 으로 암호화해 두고 JavaScript 는 볼 수 없다.

| 엔드포인트 | 제공 |
|---|---|
| `GET /api/v1/bff/login` | SDK (`BffController`) |
| `GET /api/v1/bff/callback` | SDK |
| `POST /api/v1/bff/logout` | SDK (CSRF 헤더 필요) |
| `GET /api/v1/bff/session` | **서비스**(프로필 모양이 서비스마다 다르다) |

### 동작 규칙 (SDK 가 보장하고 테스트로 고정돼 있다)

- **로그인 CSRF 방지**: `/login` 이 로그인을 시작한 브라우저에 짧은 수명의 쿠키를 심고, `/callback` 은 그 쿠키가 있어야 끝낸다. 다른 브라우저의 콜백은 코드 교환 없이 거부하고 state 도 소진된다.
- **state 는 1회용**: 저장소의 `consume` 이 행을 실제로 지운 요청만 통과한다.
- **세션 고정 방지**: 새 로그인은 이전 세션 쿠키의 세션을 지운다.
- **토큰 갱신**: 액세스 토큰 만료 60초 전부터 갱신하고, 같은 세션의 동시 갱신은 한 번만 IAM 을 부른다(리프레시 토큰은 회전되므로 두 요청이 같은 토큰을 쓰면 IAM 이 탈취로 본다). 저장은 토큰 컬럼만 바꾸는 `updateTokens` 로 한다.
- **IAM 장애·429 는 로그아웃이 아니다**: 갱신이 IAM 5xx, 429, 408, 403, 404, 연결 실패면 세션을 지우지 않고 **이번 요청만 익명**으로 둔다. IAM 이 400/401 로 명시적으로 거부할 때만 세션을 끝낸다.
- **IAM 세션 폐기 반영**: 액세스 토큰 검증이 실패하면(다른 곳에서 로그아웃) 세션을 지운다.
- **쿠키 CSRF**: 쿠키가 있는 요청이 상태를 바꾸면(POST/PUT/PATCH/DELETE) 커스텀 헤더가 있어야 하고, `Origin` 이 있다면 이 사이트와 같아야 한다.
- **복귀 경로(`return`)**: 사이트 안의 올바른 URI 경로만 허용한다(`//`, `://`, 역슬래시, 제어 문자, URI 문법 위반, 500자 초과는 첫 화면). 오픈 리다이렉트와 콜백 500 을 막는다.
- **실패한 콜백은 기존 세션 쿠키를 건드리지 않는다**: 가짜 콜백 링크 하나로 남을 로그아웃시킬 수 없다.
- 토큰 응답 오류 구분: IAM 응답 400/401 만 "거부"이고 나머지는 "일시 장애"다.

### 서비스가 할 일

1. **설정 빈**: 서비스 접두사로 바인딩한 `BffProperties` 하나(기존 환경변수 이름이 그대로 유지된다).

   ```java
   @Bean
   @ConfigurationProperties(prefix = "blog.auth")
   public BffProperties bffProperties() {
       BffProperties props = new BffProperties();
       props.setClientId("doro-blog");              // 서비스 기본값은 여기서 깔고
       props.setCookieName("doro_blog_session");    // yaml/환경변수가 그 위에 덮어쓴다
       props.setLoginCookieName("doro_blog_login");
       props.setCsrfHeader("X-Blog-Csrf");
       return props;
   }
   ```
   주요 키: `client-id`, `authorize-url`(브라우저가 가는 공개 주소), `token-url`/`revoke-url`(서버가 부르는 내부 주소 가능), `redirect-uri`(Doro 에 등록한 값과 정확히 같아야 함), `cookie-name`, `login-cookie-name`, `cookie-path`(하위 경로 서비스는 그 경로로 좁힌다. 예 `/party`), `web-base-path`(게이트웨이가 접두사를 떼는 서비스의 공개 경로. 예 `/blog`), `session-encryption-key`(base64 32바이트, `openssl rand -base64 32`), `cookie-secure`, `session-ttl`, `login-attempt-ttl`, `refresh-margin`, `csrf-header`.

2. **저장소 구현**: `BffSessionStore` 와 `BffLoginAttemptStore` 를 자기 DB(JPA 등)로 구현한다. SDK 는 트랜잭션을 열지 않으므로 **각 메서드가 그 자체로 원자적**이어야 한다(`@Transactional`).
   - `create(session, previousHash)`: 새 세션 저장 + 이전 세션 삭제를 한 트랜잭션에서.
   - `updateTokens`, `touchLastUsed`: **엔티티를 통째로 저장하지 말고** 해당 컬럼만 UPDATE 한다(그사이 회전된 토큰을 옛 값으로 덮어쓰지 않도록).
   - `consume(stateHash)`: **삭제된 행이 1 개일 때만** 값을 돌려준다.
   - `deleteAllByUserId`: 탈퇴 익명화에서 사용자의 BFF 세션을 모두 지운다.
   - 테이블: `auth_sessions`(session_hash, user_id, access_token_enc, access_expires_at, refresh_token_enc, created_at, last_used_at, expires_at), `login_attempts`(state_hash, code_verifier_enc, return_path, **browser_hash**, created_at, expires_at). 기준 구현은 doro-blog 의 `BlogBffStores` 와 마이그레이션 `V11__login_attempt_browser_binding.sql`.

3. **사용자 훅(선택)**: `BffUserHook` 빈에서 로그인 직후 서비스 사용자 행을 만들거나 갱신한다(JIT 생성). 예외를 던지면 로그인은 실패한다.

4. **세션 조회 컨트롤러**: `GET /api/v1/bff/session` 을 서비스 프로필 모양으로 제공한다. `@CurrentDoroUser` 로 현재 사용자를 받는다.

5. 서비스에 `@EnableScheduling`(만료 세션 정리용 `BffCleanup` 이 돈다).

SDK 자동 구성(`DoroBffAutoConfiguration`)은 `BffProperties`, `BffSessionStore`, `BffLoginAttemptStore` 빈이 **셋 다** 있을 때만 켜진다.

## 탈퇴 동기화 (`com.hunnit_beasts.doro.sdk.sync`)

Doro 가 알려 주는 "영구 탈퇴한 사용자 ID"(`GET /internal/v1/deleted-users`, 서비스 토큰 인증)를 주기적으로 가져와 서비스가 자기 데이터를 정리한다.
계정 삭제 정책(30일 유예 후 익명화)을 모든 서비스에 적용하는 장치다. IAM 이 잠시 죽어 있어도 다음 주기에 이어서 처리하고, 같은 ID 를 다시 받아도 결과가 같다(멱등).

서비스가 할 일:
1. `DeletedUsersProperties` 빈(접두사 예 `blog.deleted-sync`): `enabled`, `page-size`(200), `overlap`(10분: 커밋 순서가 뒤바뀌어도 놓치지 않도록 기준 시각을 앞당겨 저장), `interval`(1시간), `initial-delay`, 타임아웃.
2. `DeletedUserHandler` 빈: 그 사용자의 데이터를 정리한다(익명화, 사진 삭제, Guard 튜플 정리, `BffSessionStore.deleteAllByUserId`). 예외를 던지면 그 실행은 실패로 끝나고 다음 주기에 같은 사용자부터 이어서 처리한다.
3. `SyncCursorStore` 빈: 진행 위치(기준 시각)를 서비스 DB 에 보관한다(기준 구현: blog 의 `iam_sync_state`).
4. 설정: `doro.iam.internal-url`(Docker 내부 주소), `doro.iam.internal-token`(IAM 의 `DORO_IAM_INTERNAL_TOKENS` 에 `서비스명:토큰` 으로 등록한 값). 토큰은 환경변수로만 주고 로그에 남기지 않는다.

## 새 서비스를 붙이는 순서

1. Doro 관리자 API 로 OAuth 클라이언트를 등록한다(`first_party=true`, 서비스의 콜백 `redirect_uri`).
2. 위 "서비스가 할 일"을 구현하고 마이그레이션을 추가한다.
3. 게이트웨이에 `/서비스/` 와 `/서비스/api/` location, 쓰기·로그인 속도 제한, CSP 를 추가한다([GATEWAY_ROUTING_RULES.md](GATEWAY_ROUTING_RULES.md)).
4. IAM 서버 `.env` 에 내부 API 토큰(`DORO_IAM_INTERNAL_TOKENS`)과 Guard 서비스 토큰·네임스페이스를 등록한다.

## 통합(E2E) 시험

가짜 IAM 이 아니라 진짜 IAM·Guard·서비스·Chromium 으로 로그인 흐름을 끝까지 확인하는 시험이 `e2e/` 에 있다(`e2e/run.sh`, 설명은 `e2e/README.md`).
SDK 의 BFF 를 바꾸기 전후에 돌린다. 특히 "IAM 이 죽은 사이 토큰이 만료돼도 로그아웃되지 않는다"는 규칙은 SDK 를 일부러 옛 동작으로 바꿔 보면 이 시험이 실패하는 것으로 확인했다.

## 한계와 주의

- 형제 앱과 포털이 **같은 오리진**이면 한쪽의 XSS 가 다른 쪽 세션 API 를 부를 수 있다. 근본 해결은 서비스별 서브도메인이다(커스텀 도메인 도입 때).
- 동시 갱신 직렬화는 프로세스 안의 락이다(단일 인스턴스 전제). 다중 인스턴스가 되면 저장소의 행 잠금이나 분산 락으로 옮겨야 한다.
- SDK 는 같은 저장소의 `includeBuild` 로 소비된다. SDK 를 바꾸면 세 서비스의 CI 가 Doro main 을 체크아웃해 빌드하므로, SDK 변경을 main 에 합치기 전에 서비스 빌드가 깨지는지 확인한다.
