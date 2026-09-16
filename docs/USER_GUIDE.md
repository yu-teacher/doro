# 📚 DORO Platform 사용자 가이드 (User Guide)

> **DORO: Distributed Orchestration for ReBAC & OAuth**

본 문서는 **DORO IAM(통합 인증)**, **DORO Guard(Zanzibar ReBAC 인가)**, 그리고 **DORO SDK(서브 서비스 연동 스타터)**의 상세한 사용법과 실전 코드 예제를 제공합니다.

---

## 📑 목차
1. [Doro IAM 사용 가이드](#1-doro-iam-사용-가이드)
   - [1.1 회원가입 및 로그인](#11-회원가입-및-로그인)
   - [1.2 2단계 인증 (2FA TOTP)](#12-2단계-인증-2fa-totp)
   - [1.3 다중 계정(Multi-Account) 추가 및 전환](#13-다중-계정multi-account-추가-및-전환)
   - [1.4 RTR 토큰 갱신 및 로그아웃 킬스위치](#14-rtr-토큰-갱신-및-로그아웃-킬스위치)
   - [1.5 OAuth 2.1 & PKCE 인가 흐름](#15-oauth-21--pkce-인가-흐름)
2. [Doro Guard 사용 가이드](#2-doro-guard-사용-가이드)
   - [2.1 Zanzibar 스키마 DSL 문법 (`schema.doro`)](#21-zanzibar-스키마-dsl-문법-schemadoro)
   - [2.2 관계 튜플(Relation Tuple) 등록 및 삭제](#22-관계-튜플relation-tuple-등록-및-삭제)
   - [2.3 ReBAC Check 권한 질의 (REST / gRPC)](#23-rebac-check-권한-질의-rest--grpc)
3. [Doro SDK 연동 가이드 (Spring Boot)](#3-doro-sdk-연동-가이드-spring-boot)
   - [3.1 의존성 및 설정](#31-의존성-및-설정)
   - [3.2 `@DoroGuard` 인가 어노테이션 활용법](#32-doroguard-인가-어노테이션-활용법)
   - [3.3 `@CurrentDoroUser` 로그인 유저 자동 주입](#33-currentdorouser-로그인-유저-자동-주입)
   - [3.4 `DoroGuardClient` 프로그래밍 방식 호출](#34-doroguardclient-프로그래밍-방식-호출)
4. [신규 서브 서비스 개발자를 위한 5단계 실전 블루프린트 (Step-by-Step)](#4-신규-서브-서비스-개발자를-위한-5단계-실전-블루프린트-step-by-step)

---

## 1. Doro IAM 사용 가이드

Doro IAM 기본 포트: `http://localhost:8080`

### 1.1 회원가입 및 로그인

#### 1) 회원가입 (`POST /api/v1/auth/signup`)
```http
POST /api/v1/auth/signup HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "email": "developer@hunnit-beasts.com",
  "password": "Password123!",
  "fullName": "Doro Developer"
}
```
**응답 (201 Created)**:
```json
{
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "email": "developer@hunnit-beasts.com",
  "fullName": "Doro Developer"
}
```

#### 2) 로그인 (`POST /api/v1/auth/login`)
```http
POST /api/v1/auth/login HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "email": "developer@hunnit-beasts.com",
  "password": "Password123!",
  "clientIp": "127.0.0.1",
  "userAgent": "Mozilla/5.0 (Macintosh; Apple Mac OS)"
}
```
**일반 로그인 성공 응답 (200 OK)**:
```json
{
  "status": "SUCCESS",
  "accessToken": "eyJraWQiOiJkb3JvLWlhbS1rZXktdjEi...",
  "refreshToken": "4a123f... (RTR 회전용 UUID)",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "userIndex": 0
}
```
*비밀번호를 5회 연속 잘못 입력하면 계정이 15분간 자동으로 잠기며 423 Locked 상태가 반환됩니다.*

---

### 1.2 2단계 인증 (2FA TOTP)

#### 1) TOTP 시크릿 키 및 QR 코드 URI 발급 (`POST /api/v1/auth/2fa/setup`)
*Authorization: Bearer `<AccessToken>` 헤더 필요*
```http
POST /api/v1/auth/2fa/setup HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQi...
```
**응답 (200 OK)**:
```json
{
  "secretKey": "JBSWY3DPEHPK3PXP",
  "otpAuthUri": "otpauth://totp/Doro:developer@hunnit-beasts.com?secret=JBSWY3DPEHPK3PXP&issuer=Doro"
}
```
*Google Authenticator 또는 1Password 앱에서 위 `otpAuthUri` QR 코드를 스캔합니다.*

#### 2) 2FA 활성화 확정 (`POST /api/v1/auth/2fa/enable`)
```http
POST /api/v1/auth/2fa/enable HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQi...
Content-Type: application/json

{
  "totpCode": "123456"
}
```

#### 3) 2FA 활성화 계정의 2단계 로그인 흐름
2FA가 활성화된 계정이 `POST /api/v1/auth/login`을 호출하면 다음과 같은 응답이 반환됩니다:
```json
{
  "status": "2FA_REQUIRED",
  "twoFactorTicket": "550e8400-e29b-41d4-a716-446655440000"
}
```
위 `twoFactorTicket`과 OTP 번호로 최종 로그인을 완료합니다:
```http
POST /api/v1/auth/login/2fa HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "twoFactorTicket": "550e8400-e29b-41d4-a716-446655440000",
  "totpCode": "123456"
}
```

---

### 1.3 다중 계정(Multi-Account) 추가 및 전환

Doro IAM은 Google 스타일의 단일 세션 다중 계정(`u/0`, `u/1`...)을 네이티브로 지원합니다.

#### 1) 기존 세션에 새 계정 추가 로그인 (`POST /api/v1/auth/accounts/add`)
```http
POST /api/v1/auth/accounts/add HTTP/1.1
Host: localhost:8080
Authorization: Bearer <기존_계정_AccessToken>
Content-Type: application/json

{
  "email": "work@company.com",
  "password": "WorkPassword123!"
}
```
**응답 (200 OK)**: `userIndex: 1`이 할당된 새 토큰 발급.

#### 2) 활성 계정 전환 (`POST /api/v1/auth/accounts/switch/{uidx}`)
```http
POST /api/v1/auth/accounts/switch/1 HTTP/1.1
Host: localhost:8080
Authorization: Bearer <현재_AccessToken>
```

---

### 1.4 RTR 토큰 갱신 및 로그아웃 킬스위치

#### 1) Refresh Token Rotation (`POST /api/v1/auth/token/refresh`)
```http
POST /api/v1/auth/token/refresh HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "refreshToken": "4a123f..."
}
```
*응답으로 새로운 Access Token과 새로운 Refresh Token이 발급되며, 이전 Refresh Token은 즉시 무효화됩니다.*

#### 2) 로그아웃 & 실시간 Redis 킬스위치 (`POST /api/v1/auth/logout`)
```http
POST /api/v1/auth/logout HTTP/1.1
Host: localhost:8080
Authorization: Bearer <AccessToken>
Content-Type: application/json

{
  "refreshToken": "현재_리프레시_토큰"
}
```
*로그아웃 시 Redis에 세션 블랙리스트가 등록되어 모든 기기/서브 서비스에서 즉시 차단됩니다.*

---

### 1.5 OAuth 2.1 & PKCE 인가 흐름

#### 1) 인가 코드 요청 (`GET /oauth2/authorize`)
```
GET /oauth2/authorize?
  response_type=code&
  client_id=doro-web-client&
  redirect_uri=https://app.doro.local/callback&
  scope=openid%20profile%20email&
  state=xyzState123&
  code_challenge=E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo&
  code_challenge_method=S256
```

#### 2) 토큰 교환 (`POST /oauth2/token`)
```http
POST /oauth2/token HTTP/1.1
Host: localhost:8080
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&
client_id=doro-web-client&
code=SplxlOBeZQQYbYS6WxSbIA&
redirect_uri=https://app.doro.local/callback&
code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
```

---

## 2. Doro Guard 사용 가이드

Doro Guard 포트: REST `http://localhost:8081` / gRPC `localhost:9090`

### 2.1 Zanzibar 스키마 DSL 문법 (`schema.doro`)

Doro Guard는 구글 Zanzibar 표준 ReBAC DSL을 지원합니다.

```zanzibar
// 1. 작업 공간 (Workspace)
type workspace {
    relation admin: user
    relation member: user | admin
}

// 2. 폴더 (Folder)
type folder {
    relation owner: user
    relation parent_workspace: workspace
    relation viewer: owner | parent_workspace#member
}

// 3. 문서 (Document)
type document {
    relation owner: user
    relation direct_editor: user
    relation direct_viewer: user
    relation parent_folder: folder

    // Userset Rewrite: owner는 자동으로 editor 권한 획득
    relation editor: owner | direct_editor

    // TTU (Tuple-to-Userset): 상위 폴더의 viewer는 하위 문서의 viewer 자동 상속
    relation viewer: editor | direct_viewer | parent_folder#viewer
}

// 4. 기밀 프로젝트 (교집합 & 차집합)
type secret_project {
    relation member: user
    relation nda_signed: user
    relation blocked: user

    // 교집합(&): member와 nda_signed를 모두 만족해야 함
    // 차집합(-): blocked에 등록된 유저는 제외
    relation viewer: (member & nda_signed) - blocked
}
```

---

### 2.2 관계 튜플(Relation Tuple) 등록 및 삭제

#### 1) 단일 또는 배치 튜플 등록 (`POST /api/v1/tuples`)
```http
POST /api/v1/tuples HTTP/1.1
Host: localhost:8081
Content-Type: application/json

[
  {
    "namespace": "workspace",
    "objectId": "ws-engineering",
    "relation": "member",
    "subjectNamespace": "user",
    "subjectId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
  },
  {
    "namespace": "document",
    "objectId": "architecture-doc",
    "relation": "parent_folder",
    "subjectNamespace": "folder",
    "subjectId": "tech-specs"
  }
]
```

#### 2) 튜플 삭제 (`DELETE /api/v1/tuples`)
```http
DELETE /api/v1/tuples HTTP/1.1
Host: localhost:8081
Content-Type: application/json

[
  {
    "namespace": "workspace",
    "objectId": "ws-engineering",
    "relation": "member",
    "subjectNamespace": "user",
    "subjectId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
  }
]
```

---

### 2.3 ReBAC Check 권한 질의 (REST / gRPC)

#### 1) REST Check API (`POST /api/v1/tuples/check`)
```http
POST /api/v1/tuples/check HTTP/1.1
Host: localhost:8081
Content-Type: application/json

{
  "namespace": "document",
  "objectId": "architecture-doc",
  "relation": "viewer",
  "subjectNamespace": "user",
  "subjectId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```
**응답 (200 OK)**:
```json
{
  "allowed": true
}
```

#### 2) gRPC Check RPC (`localhost:9090`)
Protobuf 메시지:
```protobuf
CheckRequest {
  namespace: "document"
  object_id: "architecture-doc"
  relation: "viewer"
  subject_namespace: "user"
  subject_id: "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```
**gRPC 응답**:
```protobuf
CheckResponse {
  allowed: true
}
```

---

## 3. Doro SDK 연동 가이드 (Spring Boot)

서브 서비스(마이크로서비스)에서 Doro 플랫폼을 연동하는 가장 쉬운 방법입니다.

### 3.1 의존성 및 설정

#### 1) `build.gradle` 의존성 추가
```groovy
dependencies {
    implementation project(':sdk') // 또는 com.hunnit-beasts:doro-sdk:0.0.1-SNAPSHOT
}
```

#### 2) `application.yaml` 설정
```yaml
doro:
  iam:
    jwks-uri: http://localhost:8080/.well-known/jwks.json
    issuer: https://auth.doro.local
  guard:
    grpc-host: localhost
    grpc-port: 9090
    enabled: true
```

---

### 3.2 `@DoroGuard` 인가 어노테이션 활용법

컨트롤러나 서비스 메서드에 `@DoroGuard`를 붙여 인가를 선언합니다.

```java
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    // 예시 1: 속성 지정형 (SpEL 파라미터 바인딩)
    @DoroGuard(namespace = "document", object = "#docId", relation = "viewer")
    @GetMapping("/{docId}")
    public DocumentResponse getDocument(@PathVariable String docId) {
        return documentService.find(docId);
    }

    // 예시 2: 초간결 단축형 표현식
    @DoroGuard("document:#docId#editor")
    @PutMapping("/{docId}")
    public DocumentResponse updateDocument(@PathVariable String docId, @RequestBody UpdateDocRequest request) {
        return documentService.update(docId, request);
    }

    // 예시 3: 중첩 DTO 프로퍼티 바인딩
    @DoroGuard(namespace = "document", object = "#request.metadata.docId", relation = "owner")
    @DeleteMapping
    public void deleteDocument(@RequestBody DeleteDocRequest request) {
        documentService.delete(request);
    }

    // 예시 4: 대리 권한 검사 (특정 대상 유저의 권한을 확인할 때)
    @DoroGuard(namespace = "document", object = "#docId", relation = "viewer", subject = "#targetUserId")
    @GetMapping("/{docId}/delegated-check")
    public boolean checkDelegated(@PathVariable String docId, @RequestParam String targetUserId) {
        return true;
    }
}
```

---

### 3.3 `@CurrentDoroUser` 로그인 유저 자동 주입

컨트롤러 메서드 파라미터에 `@CurrentDoroUser`를 선언하면 JWT에서 검증된 사용자 객체를 바로 주입받습니다.

```java
@RestController
@RequestMapping("/api/v1/profile")
public class UserProfileController {

    // 1. 전체 DoroUser 객체 주입
    @GetMapping("/me")
    public DoroUser getMyProfile(@CurrentDoroUser DoroUser user) {
        // user.userId(), user.email(), user.sessionId(), user.userIndex()
        return user;
    }

    // 2. 유저 UUID만 바로 주입
    @GetMapping("/my-id")
    public UUID getMyUserId(@CurrentDoroUser UUID userId) {
        return userId;
    }
}
```

---

### 3.4 `DoroGuardClient` 프로그래밍 방식 호출

비즈니스 로직 중간에 직접 권한을 확인하거나 관계 튜플을 생성/삭제할 때 사용합니다.

```java
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final DoroGuardClient guardClient;

    public void createProject(String projectId, UUID creatorId) {
        // 1. 프로젝트 비즈니스 로직 수행
        projectRepository.save(new Project(projectId, creatorId));

        // 2. Zanzibar 인가 서버에 소유자 튜플 즉시 등록
        guardClient.writeTuple(
                "project", projectId, "owner",
                "user", creatorId.toString()
        );
    }

    public void archiveProject(String projectId, UUID requesterId) {
        // 3. 자바 코드로 인가 여부 직접 검사
        boolean isOwner = guardClient.check("project", projectId, "owner", requesterId.toString());
        if (!isOwner) {
            throw new AccessDeniedException("프로젝트 소유자만 아카이브할 수 있습니다.");
        }

        projectRepository.archive(projectId);
    }
}
```

---

## 4. 신규 서브 서비스 개발자를 위한 5단계 실전 블루프린트 (Step-by-Step)

새로운 마이크로서비스(예: `게시판 서비스` 또는 `파일 드라이브 서비스`)를 처음부터 만들 때 아래 5단계를 그대로 따라 하시면 5분 만에 연동이 완료됩니다.

```mermaid
flowchart LR
    S1["1단계: 권한 스키마 정의"] --> S2["2단계: build.gradle 의존성"]
    S2 --> S3["3단계: application.yaml 설정"]
    S3 --> S4["4단계: Controller @DoroGuard 적용"]
    S4 --> S5["5단계: 튜플 동기화 & 실행"]
```

### [1단계] Doro Guard에 신규 서비스 권한 규칙(Schema) 등록
Doro 소스코드를 수정할 필요 없이, REST API로 우리 서비스의 인가 규칙을 1회 등록합니다:

```bash
curl -X POST http://localhost:8081/api/v1/schemas \
  -H "Content-Type: text/plain" \
  -d '
type board_channel {
    relation admin: user
    relation member: user | admin
}

type board_post {
    relation author: user
    relation channel: board_channel
    
    // Userset Rewrite: 작성자는 자동 editor
    relation editor: author
    // TTU 상속: 채널 멤버이거나 작성자이면 조회 가능
    relation viewer: author | channel#member
}
'
```

### [2단계] 신규 서비스 `build.gradle`에 Doro SDK 추가
```groovy
dependencies {
    // Doro SDK 의존성 1줄 추가
    implementation project(':sdk') // 또는 com.hunnit-beasts:doro-sdk:1.0.0
}
```

### [3단계] 신규 서비스 `application.yaml`에 2줄 연결 설정
```yaml
doro:
  iam:
    jwks-uri: http://localhost:8080/.well-known/jwks.json # Doro IAM 공개키 주소
  guard:
    grpc-host: localhost                                  # Doro Guard gRPC 호스트
    grpc-port: 9090                                       # Doro Guard gRPC 포트
```

### [4단계] Controller에서 `@DoroGuard` & `@CurrentDoroUser`로 엔드포인트 보호
```java
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final DoroGuardClient guardClient; // SDK가 자동 Bean 등록

    // 1. 게시글 생성: 작성자 튜플 등록
    @PostMapping
    public PostResponse create(@RequestBody CreatePostRequest req, @CurrentDoroUser DoroUser user) {
        Post post = postService.create(req, user.userId());
        
        // Doro Guard에 작성자 소유권 튜플 등록
        guardClient.writeTuple("board_post", post.getId(), "author", "user", user.userId().toString());
        return new PostResponse(post);
    }

    // 2. 게시글 조회: 채널 멤버 또는 작성자만 열람 가능 (Zanzibar ReBAC 자동 검증)
    @DoroGuard("board_post:#postId#viewer")
    @GetMapping("/{postId}")
    public PostResponse get(@PathVariable String postId, @CurrentDoroUser DoroUser user) {
        return postService.get(postId);
    }

    // 3. 게시글 수정: 작성자만 수정 가능
    @DoroGuard("board_post:#postId#editor")
    @PutMapping("/{postId}")
    public PostResponse update(@PathVariable String postId, @RequestBody UpdatePostRequest req) {
        return postService.update(postId, req);
    }
}
```

### [5단계] 클라이언트 요청 테스트
클라이언트는 Doro IAM에서 발급받은 Access Token을 헤더에 넣고 호출하기만 하면 됩니다:
```bash
curl -X GET http://localhost:8082/api/v1/posts/post-100 \
  -H "Authorization: Bearer eyJraWQiOiJkb3JvLWlhbS..."
```
- **권한 보유자**: `200 OK` 및 본문 반환
- **권한 미보유자**: `403 Forbidden` 자동 차단

