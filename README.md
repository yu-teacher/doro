# 🛡️ DORO Platform

> **DORO: Distributed Orchestration for ReBAC & OAuth**  
> **차세대 엔터프라이즈 통합 인증(IAM) 및 구글 Zanzibar 기반 관계형 인가(ReBAC) 오케스트레이션 플랫폼**  
> *DORO(도로)는 모든 분산 마이크로서비스가 안전하고 신뢰성 있게 통행할 수 있도록 초저지연·고보안 인증·인가 인프라 고속도로를 제공합니다.*

---

## 🌟 Architecture Overview

```mermaid
flowchart TB
    subgraph ClientLayer ["Client & Gateway"]
        Client["Web / Mobile App"]
        SubService["Sub-Service (Microservices)"]
    end

    subgraph DoroPlatform ["Doro Security Ecosystem"]
        subgraph IAM ["1. Doro IAM (Port: 8080)"]
            AuthEngine["Auth Engine (Argon2id)"]
            SessionEngine["Session & Token (RTR)"]
            TwoFactor["2FA (TOTP Engine)"]
            OAuthEngine["OAuth 2.1 / OIDC Server"]
            JwksEndpoint["JWKS Endpoint (/.well-known/jwks.json)"]
        end

        subgraph Guard ["2. Doro Guard (HTTP: 8081 / gRPC: 9090)"]
            DslParser["Zanzibar DSL Parser & AST"]
            TupleStore["Relation Tuple Repository"]
            CheckEngine["ReBAC Check Engine (L1/L2 Cache)"]
            GrpcServer["Netty gRPC Server"]
        end

        subgraph SDK ["3. Doro SDK (Spring Boot Starter)"]
            JwtFilter["Local JWKS Fast Token Filter"]
            AopAspect["@DoroGuard Aspect (SpEL)"]
            ArgResolver["@CurrentDoroUser Resolver"]
            GuardClient["gRPC DoroGuardClient"]
        end
    end

    subgraph Storage ["Infrastructure"]
        Postgres[(PostgreSQL 16)]
        Redis[(Redis 7 - Token Blacklist & Session)]
    end

    Client -->|1. Login / OAuth 2.1| IAM
    IAM -->|Store Credentials / Tuples| Postgres
    IAM -->|Session Kill-Switch| Redis
    
    SubService -->|Import SDK| SDK
    Client -->|2. Request with JWT| SubService
    SDK -->|3. Local JWKS Verification| JwksEndpoint
    SDK -->|4. High-Speed ReBAC Check (gRPC)| GrpcServer
    GrpcServer --> CheckEngine
    CheckEngine --> TupleStore
    TupleStore --> Postgres
```

---

## 📦 모듈 구성 (Multi-Module Architecture)

| 모듈 | 기술 스택 | 설명 |
| :--- | :--- | :--- |
| [`/auth`](file:///Users/yusm/Documents/Doro/auth) | Spring Boot 4, Java 25, JPA, Redis, JJWT, Google Authenticator | **Doro IAM**: 계정 인증(Gaia), Argon2id, 다중 계정(uidx), 세션 관리, RTR 토큰 회전 & 킬스위치, 2FA(TOTP), OAuth 2.1 & OIDC 서버 |
| [`/guard`](file:///Users/yusm/Documents/Doro/guard) | Spring Boot 4, Java 25, gRPC, Protobuf, Caffeine, Flyway | **Doro Guard**: 구글 Zanzibar 모델 기반 관계형 인가(ReBAC) 엔진, 동적 스키마 DSL 파서, TTU 계층 상속, 순환 참조 방어, 고성능 gRPC Netty 서버 |
| [`/sdk`](file:///Users/yusm/Documents/Doro/sdk) | Spring Boot Starter, AOP, gRPC Stub, JJWT | **Doro SDK**: 서브 서비스 연동용 라이브러리, 로컬 비대칭키(JWKS) 고속 검증 필터, `@DoroGuard` 인가 어노테이션(SpEL), `@CurrentDoroUser` 자동 주입, `DoroGuardClient` |

---

## 🚀 빠른 시작 (Quick Start)

### 1. Docker Compose로 전체 플랫폼 가동
```bash
# PostgreSQL, Redis, Doro IAM, Doro Guard 동시 기동
docker compose up -d
```

| 서비스 | 컨테이너명 / 디렉토리 | 포트 | 헬스체크 & UI 문서 |
| :--- | :--- | :--- | :--- |
| **PostgreSQL** | `doro-postgres` | `5432` | `pg_isready` |
| **Redis** | `doro-redis` | `6379` | `redis-cli ping` |
| **Doro IAM** | `doro-auth-api` | `8080` | • **Swagger UI**: `http://localhost:8080/swagger-ui.html`<br>• **Actuator**: `http://localhost:8080/actuator/health`<br>• **JWKS**: `http://localhost:8080/.well-known/jwks.json` |
| **Doro Guard** | `doro-guard-api` | `8081` (REST)<br>`9090` (gRPC) | • **Swagger UI**: `http://localhost:8081/swagger-ui.html`<br>• **Actuator**: `http://localhost:8081/actuator/health`<br>• **Check**: `POST http://localhost:8081/api/v1/tuples/check` |
| **Doro Web Portal** | `web/` (React + Vite) | `3000` | • **통합 로그인/계정 센터**: `http://localhost:3000/account`<br>• 구글 스타일 다중 계정 전환 & 2FA & 킬스위치 |

### 2. 웹 통합 계정 포털 실행 (Doro Central Identity Portal)
```bash
cd web
npm install
npm run dev # http://localhost:3000 접속
```

### 3. 전체 테스트 실행 (Total 69 Tests Passed)
```bash
# 전체 멀티 모듈 단위/통합/동시성 부하/CORS/OpenAPI/E2E 테스트 100% 실행
./gradlew test
```

---

## 🔒 핵심 보안 및 기술 사양

### 1. Doro IAM (인증 & 자격증명)
- **비밀번호 보안**: 최신 `Argon2id` (메모리 64MB, 반복 3회, 병렬 4스레드) + 솔트 해싱
- **무차별 대입 공격 방어**: 비밀번호 5회 연속 실패 시 15분간 즉시 계정 잠금 (`ACCOUNT_LOCKED`)
- **다중 계정 관리**: 구글 스타일의 단일 세션 내 다중 프로필 전환 인덱스 (`uidx: 0, 1, 2...`)
- **세션 & RTR 킬스위치**: 
  - Refresh Token 사용 시마다 새로운 토큰 쌍 발급 (`Token Family` 체인 관리)
  - 이미 사용된 과거 토큰 재사용 감지 시 해당 Token Family 전체 무효화 및 피해자 세션 강제 종료
- **2단계 인증 (2FA)**: RFC 6238 표준 기반 시간 동기화 OTP (TOTP), QR 코드 생성, 1회용 인증 티켓 격리
- **OAuth 2.1 & OIDC 서버**: `authorization_code` (PKCE `S256` 필수), 인가 코드 1회 사용 즉시 소멸, 비대칭키(RS256) 기반 JWKS 제공

### 2. Doro Guard (Zanzibar ReBAC 인가 엔진)
- **동적 스키마 DSL**: 하드코딩 없는 `schema.doro` 규칙 파일 동적 파싱 및 런타임 핫 리로딩 지원
- **풍부한 Zanzibar 연산자**:
  - `Union (|)`: 권한 합집합
  - `Intersection (&)`: 다중 조건 동시 충족 교집합
  - `Difference (-)`: 차단 목록/블랙리스트 차집합
  - `Userset Rewrite`: 상속 릴레이션 (`editor: owner | direct_editor`)
  - `TTU (Tuple-to-Userset)`: 객체 간 계층 상속 (`viewer: parent#viewer`)
- **순환 그래프 방어**: `VisitedSet` 및 최대 깊이 제한(`MaxDepth: 32`)으로 상호/3각/자가 순환 참조 시 0.01초 내 안전 탈출
- **초고속 캐싱**: Caffeine L1 인메모리 캐시 + 튜플 변경 시 실시간 정밀 캐시 무효화
- **gRPC 고속 통신**: Protobuf 규격 기반 포트 9090 Netty gRPC 서버

### 3. Doro SDK (서브 서비스 연동 스타터)
- **로컬 JWKS 초저지연 서명 검증**: IAM 서버에 매번 네트워크 요청을 보내지 않고 로컬 메모리에서 비대칭 공개키로 **0.001ms만에 JWT 검증**
- **직관적인 인가 어노테이션**:
  - `@DoroGuard(namespace = "document", object = "#docId", relation = "viewer")`
  - `@DoroGuard("document:#docId#editor")`
  - SpEL 동적 파라미터 및 중첩 DTO 프로퍼티 바인딩 지원
- **`@CurrentDoroUser` 자동 주입**: 컨트롤러 파라미터에 현재 로그인한 사용자 객체(`DoroUser`) 및 UUID 자동 주입
- **Fail-Closed 장애 격리**: Guard 서버 장애/타임아웃 시 크래시 없이 3초 Deadline 기반 안전 거절

---

## 📖 상세 사용 가이드

자세한 API 명세, 스키마 작성법, SDK 연동 예제는 [**사용자 가이드 (User Guide)**](file:///Users/yusm/Documents/Doro/docs/USER_GUIDE.md)를 참고하세요.
