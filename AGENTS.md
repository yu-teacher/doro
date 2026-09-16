# 🛡️ DORO Platform 엔지니어링 룰 (DORO: Distributed Orchestration for ReBAC & OAuth)

> [!IMPORTANT]
> Doro 프로젝트를 작업하는 모든 AI 어시스턴트 및 개발자는 워크스페이스 루트의 공통 룰([`../AGENTS.md`](file:///Users/yusm/Documents/workspace/AGENTS.md))을 기본 준수하며, Doro 플랫폼의 특화 아키텍처 및 보안 요구사항에 따라 본 룰을 엄격히 적용해야 합니다.

---

### 1. Doro Zanzibar (ReBAC) 인가 엔진 & 스키마 동적 버전 관리
- **권한/역할 분기문 하드코딩 절대 금지**: 서비스/도메인 레이어에서 `if (role == ...)` 또는 `if (admin && target == ...)`와 같은 임의의 권한 분기문 작성을 엄격히 금지합니다. 상대적 권한 및 리소스 인가 판정은 반드시 **Doro Guard(Zanzibar) ReBAC 엔진(`Check`)에 위임**해야 합니다.
- **도메인 서비스 계층의 ReBAC 인가 위임 강제**: 엔티티 및 리소스에 대한 모든 인가/접근/수정 권한(예: 타 사용자 2FA 취소, 역할 승격/강등, 테넌트 리소스 접근 등)은 도메인 서비스 내부의 if 분기문이나 enum 비교가 아닌, 반드시 Doro Guard Client(Zanzibar `Check`)를 호출하여 판정해야 합니다.
- **2계층 버전 관리 준수**:
  - **인프라 계층(Flyway)**: 릴레이션 튜플 및 스키마 메타 테이블 구조 변경은 반드시 `guard/src/main/resources/db/migration/`의 Flyway 버전 스크립트로만 관리할 것.
  - **인가 규칙 계층(Zanzibar DSL)**: 네임스페이스, 릴레이션 및 권한 상속 규칙 변경은 `schema_definitions` 테이블을 통한 **정규 버전 관리(`SchemaService.registerSchema`)**를 거쳐야 하며, 소스 코드 하드코딩이나 임의 수동 데이터 조작을 엄격히 금지할 것.
- **Zanzibar DSL 형상 관리 및 무중단 전환**: 기본 인가 모델은 `guard/src/main/resources/schema.doro`에 형상 관리하고, 런타임에는 활성 버전(`is_active = true`)과 `AtomicReference`를 통해 서비스 재시작 없는 핫 리로드(Hot-reload) 및 즉시 롤백 가능성을 보장할 것.
- **ReBAC 권한 탐색 정합성 & 보호**: 권한 판정(Check) 및 관계 전개(Expand) 시 엔진의 최대 탐색 깊이(`max-depth: 32`)와 순환 참조(Cycle Detection) 방지 로직을 우회하지 말고, L1(Caffeine)/L2(Redis) 캐시 무효화 정합성을 유지할 것.

---

### 2. Doro IAM & Security 아키텍처
- **Argon2id 암호화 강제**: 사용자 비밀번호 암호화 및 일치 검증 시에는 항상 Spring 컨텍스트에 등록된 `CustomArgon2PasswordEncoder` (Argon2id) 빈을 사용해야 합니다.
- **인증된 사용자 주입 표준**: 컨트롤러에서 인증된 사용자를 식별할 때는 세션이나 토큰을 임의 파싱하지 않고, Spring Security 표준인 `@AuthenticationPrincipal UUID userId`를 통해서만 주입받아야 합니다.
- **세션 & RTR(Refresh Token Rotation) 원자성**: 세션 생성, 갱신, 로그아웃, 원격 세션 강제 종료(Kill-Switch) 시 PostgreSQL DB 상태(`is_active = false`)와 Redis 세션 캐시/Pub-Sub 무효화는 반드시 단일 흐름 내에서 일관되게 동기화할 것.
- **보안 식별자 보호**: 2FA/TOTP 시크릿 키, 세션 해시, 리프레시 토큰 해시 등 보안 식별자는 평문으로 노출하거나 로그에 남겨서는 안 되며, 전송 시 철저히 보호되어야 합니다.

---

### 3. Doro 다중 서비스 Flyway 스키마 히스토리 격리
- **서비스별 독립 Flyway 스크립트 경로**:
  - `auth` 모듈: `auth/src/main/resources/db/migration/V{N}__*.sql`
  - `guard` 모듈: `guard/src/main/resources/db/migration/V{N}__*.sql`
- **서비스별 히스토리 격리**: 서비스 간 충돌 방지를 위해 각 서비스는 고유한 Flyway 메타 테이블(`auth_schema_history`, `guard_schema_history`)을 사용해야 합니다.
- **DDL Validation 검증 강제**: 모든 서비스의 JPA `spring.jpa.hibernate.ddl-auto`는 항상 `validate` 상태를 유지해야 합니다.

---

### 4. Doro 관측성(Observability) 및 분산 추적 표준 (PLG 스택)
- **도커 stdout 표준 출력 & Promtail 연동**:
  - 파일 직접 쓰기나 인메모리 버퍼링을 지양하고, 모든 애플리케이션 로그는 표준 출력(`stdout`/`stderr`)으로 방출하여 Promtail이 컨테이너 메타데이터(서비스명, 컨테이너 ID)와 함께 Loki로 자동 수집할 수 있도록 할 것.
- **분산 추적(Distributed Tracing) & MDC 정합성**:
  - 인바운드 HTTP 요청 시 `X-Trace-Id` 헤더(없을 시 신규 UUID)를 추출하여 SLF4J MDC에 `traceId`, `userId`, `clientIp`를 바인딩할 것.
  - 마이크로서비스 간 통신(HTTP, gRPC, Redis Pub-Sub/Stream) 시 `traceId`를 헤더/메타데이터로 필수 전파(Propagation)하여, Grafana에서 단일 트랜잭션 전 구간의 로그를 한 번에 조회할 수 있게 보장할 것.
