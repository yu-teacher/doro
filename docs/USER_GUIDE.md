# DORO 플랫폼 사용자 가이드

> **DORO — 인증(IAM), 인가(Guard), 서비스 연동(SDK)을 한곳에서**
>
> 이 문서는 **커밋 `d2d695e`** 시점의 소스 코드(`auth/`, `guard/`, `sdk/`, `web/`, `gateway/`)와 테스트를 직접 읽고 작성했습니다.
> **문서와 코드가 다르면 코드가 맞습니다.** 코드로 확인하지 못한 내용에는 `(미검증)` 표시를 붙였습니다.
> 개요와 운영 이야기는 [`README.md`](../README.md), 에이전트용 요약은 [`DORO_AGENT_GUIDE.md`](DORO_AGENT_GUIDE.md)를 보세요. 이 가이드는 "무엇을 어떻게 호출하는가"에 집중한 튜토리얼입니다.

---

## 목차

0. [시작하기 전에](#0-시작하기-전에)
1. [Doro IAM](#1-doro-iam)
   - [1.1 가입과 로그인](#11-가입과-로그인)
   - [1.2 2단계 인증](#12-2단계-인증)
   - [1.3 토큰 갱신과 로그아웃](#13-토큰-갱신과-로그아웃)
   - [1.4 세션 관리](#14-세션-관리)
   - [1.5 내 정보와 비밀번호](#15-내-정보와-비밀번호)
   - [1.6 관리자 API](#16-관리자-api)
   - [1.7 응답 형식과 에러 코드](#17-응답-형식과-에러-코드)
   - [1.8 요청 제한과 계정 잠금](#18-요청-제한과-계정-잠금)
   - [1.9 여러 계정 전환](#19-여러-계정-전환)
2. [OAuth 2.1 과 OIDC](#2-oauth-21-과-oidc)
   - [2.1 개요](#21-개요)
   - [2.2 클라이언트 등록](#22-클라이언트-등록)
   - [2.3 인가 요청](#23-인가-요청)
   - [2.4 토큰 엔드포인트](#24-토큰-엔드포인트)
   - [2.5 id_token 과 userinfo 와 discovery](#25-id_token-과-userinfo-와-discovery)
   - [2.6 처음부터 끝까지 curl 예제](#26-처음부터-끝까지-curl-예제)
   - [2.7 보안 속성](#27-보안-속성)
   - [2.8 설정과 운영](#28-설정과-운영)
3. [Doro Guard](#3-doro-guard)
   - [3.1 개념과 표기법](#31-개념과-표기법)
   - [3.2 스키마 DSL](#32-스키마-dsl)
   - [3.3 튜플 API](#33-튜플-api)
   - [3.4 Check API](#34-check-api)
   - [3.5 스키마 조회와 등록](#35-스키마-조회와-등록)
   - [3.6 gRPC 와 Expand](#36-grpc-와-expand)
   - [3.7 서비스 토큰](#37-서비스-토큰)
   - [3.8 입력 제한과 에러 응답](#38-입력-제한과-에러-응답)
   - [3.9 기본 스키마](#39-기본-스키마)
4. [Doro SDK](#4-doro-sdk)
   - [4.1 의존성](#41-의존성)
   - [4.2 설정 속성 전체표](#42-설정-속성-전체표)
   - [4.3 필터가 하는 일과 하지 않는 일](#43-필터가-하는-일과-하지-않는-일)
   - [4.4 CurrentDoroUser](#44-currentdorouser)
   - [4.5 DoroGuard](#45-doroguard)
   - [4.6 DoroGuardClient](#46-doroguardclient)
   - [4.7 기본 예외 처리](#47-기본-예외-처리)
   - [4.8 세션 폐기 확인](#48-세션-폐기-확인)
5. [새 서브 서비스 블루프린트](#5-새-서브-서비스-블루프린트)
6. [문제 해결](#6-문제-해결)
7. [부록](#7-부록)

---

## 0. 시작하기 전에

### 구성요소와 기본 주소

| 구성요소 | 위치 | 기본 주소(로컬 `docker compose up -d`) |
|---|---|---|
| IAM (인증, 세션, 토큰 발급, OAuth/OIDC) | `auth/` | `http://localhost:8080` |
| Guard (ReBAC 인가 엔진) | `guard/` | REST `http://localhost:8081`, gRPC `localhost:9090` (기본 `127.0.0.1` 바인딩) |
| SDK (서브 서비스용 Spring Boot 라이브러리) | `sdk/` | 라이브러리 |
| 포털 (React) | `web/` | `http://localhost:3000` |
| 게이트웨이 (nginx) | `gateway/nginx.conf` | 443 (compose 프로젝트 밖) |

이 가이드의 예제는 아래 셸 변수를 쓴다고 가정합니다. `curl`과 `jq`가 필요합니다.

```bash
IAM=http://localhost:8080
GUARD=http://localhost:8081
```

> Guard는 사용자 로그인이 없고 **서비스 토큰**으로만 호출자를 구분합니다. 기본값(`OFF`)에서는 인증 없이 열려 있으므로 외부에 노출하면 안 됩니다. 게이트웨이도 Guard를 프록시하지 않습니다. ([3.7](#37-서비스-토큰))

### 공통 규칙 두 가지

1. **성공 응답은 봉투(envelope)로 감쌉니다.** `data` 안에 실제 내용이 들어갑니다.
   ```json
   { "success": true, "data": { }, "timestamp": "2026-10-02T00:00:00Z" }
   ```
   본문이 없는 성공은 `{ "success": true, "timestamp": "..." }` 입니다. (`null` 필드는 응답에서 빠집니다.)
2. **에러 응답은 봉투와 다른 모양**입니다. `success` 필드가 없고 `code`는 **enum 이름**입니다. 자세한 형식은 [1.7](#17-응답-형식과-에러-코드).

예외가 몇 개 있습니다. OAuth 토큰 엔드포인트의 폼 요청은 RFC 6749 형식, `/oauth2/userinfo`와 `/.well-known/*`는 봉투 없이 그대로, `GET /api/v1/sessions/current`와 `GET /api/v1/admin/authz`는 본문 없는 `204` 입니다.

### 상태 점검

```bash
curl -s $IAM/health            # {"status":"UP"}
curl -s $IAM/actuator/health   # IAM
curl -s $GUARD/actuator/health # Guard
```
IAM 은 로컬 개발용으로 `/swagger-ui.html`, `/v3/api-docs` 에 Swagger 를 제공하지만 **기본은 꺼져 있습니다**(`DORO_IAM_SWAGGER_ENABLED=true` 로 켭니다). Guard도 같은 경로에 Swagger를 제공합니다.

---

## 1. Doro IAM

기본 주소 `http://localhost:8080`. 사용자 대상 API는 모두 `/api/v1/**` 아래에 있고 JSON은 camelCase 입니다.

### 1.1 가입과 로그인

#### 가입 `POST /api/v1/auth/signup`

```bash
curl -s -X POST $IAM/api/v1/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","password":"Password123!","name":"Doro Dev"}'
```

```json
{ "success": true, "data": { "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6" }, "timestamp": "2026-10-02T00:00:00Z" }
```
HTTP 상태는 `201 Created` 입니다.

| 필드 | 규칙 |
|---|---|
| `email` | 이메일 형식. **앞뒤 공백 제거 후 소문자로 저장**하고, 중복 검사는 대소문자를 무시합니다 |
| `password` | 8~64자 (길이만 검사) |
| `name` | 2~50자 (필드 이름은 `name` 입니다. `fullName` 은 없습니다) |

- 실패: `400 INVALID_INPUT_VALUE`(검증 실패, `details` 포함), `409 EMAIL_ALREADY_EXISTS`.
- 가입은 로그인까지 해 주지 않습니다. 새 계정의 역할은 항상 `USER` 입니다.
- 가입 시 IAM이 Guard에 사용자 튜플을 동기화합니다. 이 동기화가 실패해도 가입 자체는 성공합니다(실패는 로그에만 남습니다).

#### 로그인 `POST /api/v1/auth/login`

```bash
curl -s -X POST $IAM/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","password":"Password123!","deviceInfo":"my laptop"}'
```
`deviceInfo`는 선택입니다. 비우면 `User-Agent`에서 `"Mac (Chrome)"` 같은 문자열을 만들어 저장합니다. 클라이언트 IP와 `User-Agent`는 서버가 요청에서 직접 읽습니다(본문으로 보내는 필드가 아닙니다).

**2FA가 꺼진 계정** — 토큰이 `data.tokens` 안에 들어 있습니다.
```json
{
  "success": true,
  "data": {
    "requires2fa": false,
    "tokens": {
      "accessToken": "eyJraWQiOiJkb3JvLWlhbS1rZXktMjAyNi12MSIs...",
      "refreshToken": "q3xJ...(48바이트 랜덤, base64url)",
      "tokenType": "Bearer",
      "expiresIn": 900,
      "sessionId": "9d2f6b1e-0f0a-4b0e-9f43-1d3c9d1c7a11",
      "userIndex": 0
    }
  },
  "timestamp": "2026-10-02T00:00:00Z"
}
```

**2FA가 켜진 계정** — 토큰 대신 임시 티켓이 옵니다. 다음 단계는 [1.2](#12-2단계-인증)입니다.
```json
{ "success": true, "data": { "requires2fa": true, "tempTicket": "550e8400-e29b-41d4-a716-446655440000" }, "timestamp": "2026-10-02T00:00:00Z" }
```
(`status: "SUCCESS"` 같은 필드는 없습니다. 비밀번호가 맞아도 2FA 계정은 이 시점에 토큰을 받지 못합니다.)

토큰을 변수에 담아 쓰는 습관을 들이면 편합니다.
```bash
TOKEN=$(curl -s -X POST $IAM/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","password":"Password123!"}' | jq -r '.data.tokens.accessToken')
curl -s $IAM/api/v1/users/me -H "Authorization: Bearer $TOKEN"
```

**`TokenResponse` 필드**

| 필드 | 설명 |
|---|---|
| `accessToken` | JWT(RS256). 수명은 `expiresIn`초 |
| `refreshToken` | 불투명 랜덤 문자열, 30일. 사용할 때마다 새 값으로 **회전**됩니다 |
| `tokenType` | 항상 `Bearer` |
| `expiresIn` | 액세스 토큰 수명(초). 기본 **900초(15분)**, 환경변수 `DORO_IAM_ACCESS_TOKEN_TTL_SECONDS` |
| `sessionId` | 이 로그인의 세션 ID(JWT의 `sid`와 같음) |
| `userIndex` | 이 사용자의 활성 세션 중 부여된 순번(JWT의 `uidx`) |

**액세스 토큰(JWT) 내용**: 헤더 `alg=RS256`, `typ=JWT`, `kid`(기본 `doro-iam-key-2026-v1`). 클레임은 `iss`(`DORO_IAM_ISSUER`, 기본 `https://auth.doro.local`), `sub`(사용자 UUID), `email`, `sid`, `uidx`, `role`(`USER`/`ADMIN`/`SUPER_ADMIN`), `iat`, `exp` 입니다. `aud`/`jti`/`nbf`는 없습니다. 공개키는 `GET /.well-known/jwks.json` 에서 받습니다.

#### 계정 존재 조회 `POST /api/v1/auth/lookup`

```bash
curl -s -X POST $IAM/api/v1/auth/lookup -H 'Content-Type: application/json' -d '{"email":"dev@example.com"}'
# {"success":true,"data":{"email":"dev@example.com","name":"Doro Dev","profileImageUrl":null},"timestamp":"..."}
```
없으면 `404 USER_NOT_FOUND`. 정지된 계정이어도 응답은 같고, 정지 사실은 로그인에서 올바른 비밀번호를 낸 뒤에야 `403 ACCOUNT_SUSPENDED` 로 알려 줍니다. 포털의 "이메일 → 비밀번호" 2단계 로그인 화면용입니다. 계정 존재 여부가 드러나므로 IP당 10분에 30회로 제한됩니다. (`profileImageUrl: null`로 나가는지는 `(미검증)`입니다.)

#### 로그인 실패 케이스

| 상황 | 응답 |
|---|---|
| 없는 이메일 / 틀린 비밀번호 | `401 INVALID_CREDENTIALS` (둘을 구분하지 않음) |
| 비밀번호 5회 연속 실패 후 | `403 ACCOUNT_LOCKED` (15분, 올바른 비밀번호도 거부) |
| 이용 정지 계정 | `403 ACCOUNT_SUSPENDED` |
| 요청 과다(IP 단위) | `429 TOO_MANY_REQUESTS` + `Retry-After` 헤더 |

> **같은 기기에서 다시 로그인하면 이전 세션이 끝납니다.** 같은 IP + 같은 `User-Agent`의 이전 활성 세션은 새 로그인 때 폐기됩니다(리프레시 토큰 폐기 포함). `curl`로 두 번 로그인하면 첫 토큰이 죽습니다. 사용자당 활성 세션은 최대 10개(`DORO_IAM_SESSION_MAX_ACTIVE_PER_USER`, 0 이하는 무제한)이고, 넘으면 가장 오래된 세션부터 종료됩니다.

---

### 1.2 2단계 인증

TOTP(RFC 6238): HMAC-SHA1, 30초, 6자리, 시간 오차 ±1스텝 허용. **같은 코드(또는 이전 스텝 코드)의 재사용은 거부**합니다(IAM 인스턴스 메모리 기준).

활성화는 두 단계입니다. `setup` 만으로는 켜지지 않고, `verify`로 코드를 확인해야 켜집니다. 설정만 하고 중단해도 로그인에는 영향이 없습니다.

#### 1) 시크릿 발급 `POST /api/v1/auth/2fa/setup` (Bearer)

```bash
curl -s -X POST $IAM/api/v1/auth/2fa/setup -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"currentPassword":"<현재 비밀번호>"}'
```
```json
{
  "success": true,
  "data": {
    "secret": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
    "qrUri": "otpauth://totp/Doro:dev%40example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Doro&algorithm=SHA1&digits=6&period=30"
  },
  "timestamp": "2026-10-02T00:00:00Z"
}
```
`qrUri`를 인증 앱(Google Authenticator, 1Password 등)에 등록하세요. 필드 이름은 `secret`, `qrUri` 입니다. 이미 활성화된 계정이 다시 호출하면 `400 INVALID_INPUT`. 활성화 전에 다시 호출하면 대기 시크릿이 새 값으로 바뀝니다.

#### 2) 활성화 확정 `POST /api/v1/auth/2fa/verify` (Bearer)

```bash
curl -s -X POST $IAM/api/v1/auth/2fa/verify -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"code":"123456"}'
# {"success":true,"timestamp":"..."}
```
대기 중인 시크릿이 있으면 이 코드로 확인되는 순간 활성화됩니다. 이미 활성화된 계정에서는 코드만 검증합니다(재인증용). 코드가 틀리면 `401 INVALID_2FA_CODE` 이고 **계정 잠금 카운터에 합산**됩니다. 활성화 여부는 `GET /api/v1/users/me`의 `hasTotp`로 확인합니다.

#### 3) 2FA 계정으로 로그인

```bash
# 1단계: 비밀번호 -> 임시 티켓
curl -s -X POST $IAM/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","password":"Password123!"}'
# {"success":true,"data":{"requires2fa":true,"tempTicket":"550e8400-..."},"timestamp":"..."}

# 2단계: 티켓 + OTP -> 토큰
curl -s -X POST $IAM/api/v1/auth/2fa/login -H 'Content-Type: application/json' \
  -d '{"tempTicket":"550e8400-e29b-41d4-a716-446655440000","code":"123456","deviceInfo":"my laptop"}'
```
**주의: `/2fa/login` 의 `data`는 토큰 객체 그 자체**입니다(`data.tokens`가 아님).
```json
{ "success": true, "data": { "accessToken": "...", "refreshToken": "...", "tokenType": "Bearer", "expiresIn": 900, "sessionId": "...", "userIndex": 0 }, "timestamp": "..." }
```
- 임시 티켓은 **5분** 유효하고, 코드를 5회 틀리면 폐기됩니다(처음부터 다시 로그인). 모르는/만료된 티켓은 `400 INVALID_TOKEN`.
- 티켓은 IAM 프로세스 메모리에 있습니다(재시작하면 사라지고, 동시 보관 상한 `DORO_IAM_TWO_FACTOR_MAX_PENDING_TICKETS` 기본 10000을 넘으면 `429`).
- OTP 실패도 비밀번호 실패와 같은 카운터에 합산되어 5회면 계정이 15분 잠깁니다.
- `code`는 정확히 6자리 숫자여야 합니다(아니면 `400 INVALID_INPUT_VALUE`).

#### 4) 해제 `POST /api/v1/auth/2fa/disable` (Bearer)

현재 유효한 OTP 코드가 필요합니다. 로그인된 세션만으로는 해제할 수 없습니다.
```bash
curl -s -X POST $IAM/api/v1/auth/2fa/disable -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"code":"123456"}'
```
코드가 틀리면 `401 INVALID_2FA_CODE`. 2FA가 활성화돼 있지 않으면(대기 시크릿만 있으면 그것을 지우고) `200`.

> **방금 쓴 코드는 다시 못 씁니다.** `verify` 직후 같은 30초 구간의 같은 코드로 로그인/해제를 시도하면 `INVALID_2FA_CODE` 입니다. 다음 코드가 나올 때까지 기다리세요.

관리자가 다른 사용자의 2FA를 초기화하는 API는 [1.6](#16-관리자-api)에 있습니다.

---

### 1.3 토큰 갱신과 로그아웃

#### 갱신 `POST /api/v1/auth/token/refresh` (인증 헤더 불필요)

```bash
curl -s -X POST $IAM/api/v1/auth/token/refresh -H 'Content-Type: application/json' \
  -d '{"refreshToken":"q3xJ..."}'
```
응답 `data`는 `TokenResponse`(`accessToken`, `refreshToken`, `tokenType`, `expiresIn`, `sessionId`, `userIndex`)입니다. **리프레시 토큰은 매번 새로 발급**되므로 반드시 새 값으로 교체하세요.

- **회전(RTR)**: 사용한 토큰은 즉시 폐기됩니다. 동시에 두 요청이 같은 토큰을 내면 하나만 성공하고 나머지는 `400 INVALID_TOKEN`.
- **재사용 감지**: 이미 폐기된 토큰이 다시 오면 탈취로 보고 그 토큰 계열(family)의 모든 토큰을 폐기하고 **세션을 종료**한 뒤 `400 TOKEN_REUSE_DETECTED`를 돌려줍니다. 이후 그 계열의 어떤 토큰도 거부됩니다. (다중 탭 경합을 봐줄 유예 `doro.iam.jwt.refresh-reuse-grace-seconds`는 기본 0=끔.)
- 세션 만료는 **슬라이딩**입니다. 갱신할 때마다 만료가 "지금 + 30일"로 늘어납니다(무활동 30일 후 만료).
- 그 밖의 오류: 모르는 토큰 `400 INVALID_TOKEN`, 토큰 수명 만료 `401 TOKEN_EXPIRED`, 세션이 이미 종료됨 `401 SESSION_EXPIRED`, 계정이 정지됨 `403 ACCOUNT_SUSPENDED`.
- 새 액세스 토큰의 `role`은 갱신 시점의 DB 값입니다.

#### 로그아웃 `POST /api/v1/auth/logout` (Bearer)

```bash
curl -s -X POST $IAM/api/v1/auth/logout -H "Authorization: Bearer $TOKEN"                      # 현재 세션 종료
curl -s -X POST "$IAM/api/v1/auth/logout?sessionId=<세션UUID>" -H "Authorization: Bearer $TOKEN" # 내 다른 세션 종료
```
- `sessionId`를 생략하면 토큰의 `sid`(현재 세션)를 종료합니다.
- 본인 소유가 아니거나 이미 끝난 세션은 `404 SESSION_NOT_FOUND`(남의 세션 존재 여부를 알려주지 않기 위함)입니다.
- 종료는 한 흐름으로 **세션 비활성화 + 리프레시 토큰 폐기 + Redis 블랙리스트/킬스위치 발행**을 합니다. IAM은 이후 그 세션의 액세스 토큰을 즉시 거부합니다(`401`).
- **서브 서비스(SDK)에는 즉시 반영되지 않습니다.** SDK의 `revocation-check`를 켜지 않으면 액세스 토큰이 만료될 때까지 서브 서비스에서 유효합니다. [4.8](#48-세션-폐기-확인)을 보세요.

---

### 1.4 세션 관리

모두 Bearer가 필요합니다. 응답의 세션 항목(`SessionResponse`):

```json
{
  "sessionId": "9d2f6b1e-0f0a-4b0e-9f43-1d3c9d1c7a11",
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userIndex": 0,
  "deviceInfo": "Mac (Chrome)",
  "ipAddress": "203.0.113.7",
  "isActive": true,
  "lastActiveAt": "2026-10-02T00:00:00Z",
  "expiresAt": "2026-11-01T00:00:00Z",
  "createdAt": "2026-10-02T00:00:00Z"
}
```

| 호출 | 설명 |
|---|---|
| `GET /api/v1/sessions` | 내 활성 세션 목록(`data`는 배열, 최신순) |
| `DELETE /api/v1/sessions/{sessionId}` | 내 세션 하나 종료. 남의 세션/이미 끝난 세션은 `404 SESSION_NOT_FOUND` |
| `POST /api/v1/sessions/revoke-others?currentSessionId=<유지할 세션>` | 지정한 세션만 남기고 나머지 전부 종료. `currentSessionId`는 필수이며 내 활성 세션이어야 합니다(아니면 `404`) |
| `GET /api/v1/sessions/current` | 이 토큰의 세션이 **DB 기준으로 아직 유효한지** 확인. 유효하면 `204`(본문 없음), 아니면 `401`(`SESSION_EXPIRED`) / `403`(`ACCOUNT_SUSPENDED`). 세션을 갱신하지 않음 |

```bash
curl -s $IAM/api/v1/sessions -H "Authorization: Bearer $TOKEN"
curl -s -o /dev/null -w '%{http_code}\n' $IAM/api/v1/sessions/current -H "Authorization: Bearer $TOKEN"   # 204
```

`GET /api/v1/sessions/current`는 **서브 서비스의 SDK가 세션 폐기를 확인할 때 호출하는 엔드포인트**입니다. OAuth 액세스 토큰으로도 호출할 수 있습니다([2.7](#27-보안-속성)).

OAuth로 로그인한 세션도 이 목록에 나타나고(`deviceInfo` 가 `OAuth2 Client: <client_id>`, `ipAddress` 가 `OAuth2`) 세션 상한에도 포함됩니다.

---

### 1.5 내 정보와 비밀번호

```bash
# 조회
curl -s $IAM/api/v1/users/me -H "Authorization: Bearer $TOKEN"
# 수정 (null/생략한 필드는 그대로, profileImageUrl 을 "" 로 보내면 이미지 제거)
curl -s -X PATCH $IAM/api/v1/users/me -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"New Name","profileImageUrl":"https://example.com/me.png"}'
# 비밀번호 변경
curl -s -X PUT $IAM/api/v1/users/me/password -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"Password123!","newPassword":"NewPassword456!"}'
```
비밀번호 변경은 현재 비밀번호를 다시 확인합니다. 틀리면 `401 INVALID_CREDENTIALS` 이고 **로그인과 같은 실패 횟수(5회 시 15분 잠금 `403 ACCOUNT_LOCKED`)에 합산**되며, IP 단위 요청 제한도 로그인과 같은 규칙이 적용됩니다. 성공하면 현재 세션을 제외한 모든 세션이 종료됩니다.

`UserProfileResponse`:
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "email": "dev@example.com",
  "name": "New Name",
  "profileImageUrl": "https://example.com/me.png",
  "status": "ACTIVE",
  "role": "USER",
  "hasTotp": false,
  "createdAt": "2026-10-02T00:00:00Z"
}
```
- `name`은 2~50자(공백만이면 무시). `profileImageUrl`은 `data:` URL 포함 최대 1,048,576자(`DORO_IAM_PROFILE_IMAGE_MAX_LENGTH`)이고 `https://` 주소 또는 이미지 `data:` URL(png·jpeg·gif·webp·svg+xml)만 허용합니다. 넘거나 허용되지 않는 형식이면 `400 INVALID_INPUT_VALUE`.
- 비밀번호 변경: `newPassword` 8~64자. 현재 비밀번호가 틀리면 `401 INVALID_CREDENTIALS`(잠금 카운터에는 합산되지 않음). 성공하면 **현재 세션을 제외한 내 모든 세션이 종료**됩니다.

---

### 1.6 관리자 API

관리자 API는 **JWT 역할(`ADMIN`/`SUPER_ADMIN`) + Guard 재검사**를 둘 다 통과해야 합니다. 역할 클레임은 토큰 수명(기본 15분)만큼 낡을 수 있어서, 최종 판정은 Guard에 맡깁니다.

| 호출 | Guard 판정 | 설명 |
|---|---|---|
| `GET /api/v1/admin/users` | `system:doro#admin` | 전체 사용자 목록(`UserProfileResponse[]`) |
| `PATCH /api/v1/admin/users/{userId}/role` | `system:doro#manage_roles` | 본문 `{"role":"ADMIN"}` (`USER`/`ADMIN`/`SUPER_ADMIN`, 대소문자 무관). 자기 자신은 `400`. 성공하면 대상의 **모든 세션이 종료**되고 Guard 튜플이 갱신됩니다 |
| `DELETE /api/v1/admin/users/{userId}/2fa` | `user:{대상}#can_reset_2fa` | 대상의 2FA(대기 시크릿 포함)와 실패 카운터를 지우고 대상의 모든 세션을 종료 |
| `GET /api/v1/admin/authz` | `system:doro#admin` | 성공하면 `204`. 게이트웨이가 `/loki/`를 보호할 때 `auth_request`로 호출 |
| `POST/GET/DELETE /api/v1/admin/oauth/clients` | `system:doro#admin` | OAuth 클라이언트 관리 ([2.2](#22-클라이언트-등록)) |

기본 스키마상 `manage_roles`는 `super_admin`만 가지므로 **역할 변경은 사실상 `SUPER_ADMIN` 전용**입니다.

```bash
curl -s -X PATCH $IAM/api/v1/admin/users/$TARGET_ID/role -H "Authorization: Bearer $SUPER_TOKEN" \
  -H 'Content-Type: application/json' -d '{"role":"ADMIN"}'
```

**응답 코드**: 토큰이 없으면 `401`(본문 없음), 역할이 `ADMIN` 미만이면 `403`(본문 없음), 역할은 맞지만 Guard가 거부하면 `403 ACCESS_DENIED`(에러 본문 있음). Guard에 닿지 못해도 fail-closed로 `403` 입니다.

**역할 → Guard 튜플** (가입/역할 변경/IAM 기동 시 자동 동기화):

| 역할 | 쓰는 튜플 |
|---|---|
| `SUPER_ADMIN` | `system:doro#super_admin@user:{id}`, `user:{id}#super_manager@system:doro#super_admin` |
| `ADMIN` | `system:doro#admin@user:{id}`, `user:{id}#manager@system:doro#admin`, `user:{id}#super_manager@system:doro#super_admin` |
| `USER` | `user:{id}#manager@system:doro#admin`, `user:{id}#super_manager@system:doro#super_admin` |

그래서 일반 ADMIN은 USER와 ADMIN의 2FA는 초기화할 수 있지만 SUPER_ADMIN의 것은 못 합니다.

**첫 관리자 만들기**: 부트스트랩 API는 없습니다. 가입한 계정의 `users.role`을 DB에서 바꾼 뒤 **IAM을 재시작**해 기동 시 동기화가 Guard 튜플을 쓰게 하고, **다시 로그인**해서 `role` 클레임이 `ADMIN`인 토큰을 받으세요.
```bash
docker exec doro-postgres psql -U doro_admin -d doro_auth \
  -c "update users set role='SUPER_ADMIN' where email='admin@example.com';"
docker compose restart auth-api
```
(DB 이름·사용자는 `.env`의 `AUTH_DB`/`POSTGRES_USER` 값에 맞추세요. 이것은 **데이터** 수정일 뿐 스키마 변경이 아닙니다.)

> **게이트웨이 경유 주의**: 저장소의 `gateway/nginx.conf`는 관리자 API 중 `/api/v1/admin/users`와 `/api/v1/admin/oauth/`만 IAM으로 보냅니다(`/api/v1/admin/oauth/` 라우트는 최근 추가됐고, 이 라우트가 없으면 요청이 블로그 백엔드로 흘러갑니다). **게이트웨이는 compose 밖이라 서버에 수동 반영해야 하며**, 서버에 실제로 반영된 nginx 설정은 확인하지 못했습니다 `(미검증)`. 반영 전에는 `/iam/` prefix(`/iam/api/v1/admin/oauth/clients`)나 IAM에 직접 붙어서 호출하세요.

---

### 1.7 응답 형식과 에러 코드

#### 에러 본문 (IAM)

```json
{
  "timestamp": "2026-10-02T00:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "code": "INVALID_CREDENTIALS",
  "message": "이메일 또는 비밀번호가 올바르지 않습니다.",
  "path": "/api/v1/auth/login"
}
```
- `code`는 **enum 이름**입니다. (`AUTH_40102` 같은 내부 문자열은 응답에 나가지 않습니다.)
- 입력 검증 실패는 `code: "INVALID_INPUT_VALUE"`에 `details`가 붙습니다. 비밀번호/시크릿/토큰/코드 필드는 `rejectedValue`를 비워 돌려줍니다.
  ```json
  { "status": 400, "error": "Bad Request", "code": "INVALID_INPUT_VALUE", "message": "요청 파라미터 유효성 검증에 실패했습니다.", "path": "/api/v1/auth/signup",
    "details": [ { "field": "password", "reason": "비밀번호는 8자 이상 64자 이하이어야 합니다." } ] }
  ```
- **본문 없는 응답**: Bearer가 없거나 만료/위조된 토큰으로 인증이 필요한 API를 부르면 `401`이 **본문 없이** 나갑니다(만료와 부재를 본문으로 구분할 수 없습니다. 보통 갱신을 시도하면 됩니다). 역할 부족 `403`도 본문이 없습니다.
- **429**는 요청 제한 필터가 직접 씁니다(`timestamp`/`path` 없음, `Retry-After` 헤더 포함):
  `{"status":429,"error":"Too Many Requests","code":"TOO_MANY_REQUESTS","message":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."}`

#### 주요 에러 코드

| HTTP | `code` | 언제 |
|---|---|---|
| 400 | `INVALID_INPUT_VALUE` | Bean Validation 실패, 본문/파라미터 형식 오류 |
| 400 | `INVALID_INPUT` | 서비스 로직의 입력 오류(예: 이미 활성화된 2FA, 자기 역할 변경, OAuth 파라미터 오류) |
| 400 | `INVALID_TOKEN` | 모르는/이미 쓴 리프레시 토큰, 만료/잘못된 2FA 임시 티켓, OAuth `invalid_grant` |
| 400 | `TOKEN_REUSE_DETECTED` | 리프레시 토큰 재사용 감지(세션 종료됨) |
| 401 | `UNAUTHORIZED` | 로그인이 필요한 요청인데 인증 정보 없음(컨트롤러가 직접 던지는 경우) |
| 401 | `INVALID_CREDENTIALS` | 이메일/비밀번호 불일치, 현재 비밀번호 불일치 |
| 401 | `TOKEN_EXPIRED` | 리프레시 토큰 수명 만료 |
| 401 | `SESSION_EXPIRED` | 세션 종료/만료 |
| 401 | `INVALID_2FA_CODE` | OTP 코드 불일치 |
| 403 | `ACCESS_DENIED` | Guard가 거부 |
| 403 | `ACCOUNT_LOCKED` | 연속 실패로 잠김 (**423이 아닙니다**) |
| 403 | `ACCOUNT_SUSPENDED` | 이용 정지 계정 |
| 404 | `USER_NOT_FOUND`, `SESSION_NOT_FOUND`, `OAUTH_CLIENT_NOT_FOUND` | |
| 404/405/415 | `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` | 존재하지 않는 경로/메서드/Content-Type |
| 409 | `EMAIL_ALREADY_EXISTS`, `OAUTH_CLIENT_ALREADY_EXISTS` | |
| 429 | `TOO_MANY_REQUESTS` | 요청 제한, 또는 2FA 티켓/인가 코드 저장 한도 초과 |
| 500 | `INTERNAL_SERVER_ERROR` | 처리되지 않은 오류(스택트레이스는 로그에만) |

---

### 1.8 요청 제한과 계정 잠금

#### IP 단위 요청 제한 (고정 윈도우, 단일 인스턴스 메모리)

| 대상 | 기본 한도 | 속성 |
|---|---|---|
| `POST /auth/login`, `POST /auth/2fa/login` (같은 버킷) | 10분에 **20회** | `DORO_IAM_RATE_LIMIT_LOGIN_MAX` |
| `POST /auth/lookup` | 10분에 30회 | `doro.iam.rate-limit.lookup-max` |
| `POST /auth/signup` | 1시간에 10회 | `doro.iam.rate-limit.signup-max` |
| `POST /oauth2/token`, **Bearer 없는** `GET /oauth2/authorize` (같은 버킷) | 10분에 60회 | `DORO_IAM_RATE_LIMIT_TOKEN_MAX` |

전체 끄기: `DORO_IAM_RATE_LIMIT_ENABLED=false`. 초과하면 `429` + `Retry-After`(다음 윈도우까지 남은 초).
클라이언트 IP는 **신뢰 프록시**(`DORO_IAM_TRUSTED_PROXIES`, 기본 루프백 + `172.16.0.0/12`)에서 온 요청의 `X-Real-IP`만 믿고, 그 외에는 TCP 접속 주소를 씁니다. `X-Forwarded-For`는 읽지 않습니다.

#### 계정 잠금

- 비밀번호 **5회 연속 실패 → 15분 잠금**(`403 ACCOUNT_LOCKED`). 잠금 중에는 올바른 비밀번호도 거부됩니다. 15분이 지나면 자동 해제됩니다.
- 2FA 코드 실패와 `2fa/verify`·`2fa/disable` 실패도 같은 카운터에 합산됩니다.
- 2FA 계정은 OTP까지 통과해야 카운터가 0으로 돌아갑니다(비밀번호만 맞췄을 때는 초기화되지 않음). 비밀번호 변경 성공, 관리자의 2FA 초기화도 카운터를 지웁니다.

#### 토큰 수명 요약

| 항목 | 값 |
|---|---|
| 액세스 토큰 | 기본 15분. `DORO_IAM_ACCESS_TOKEN_TTL_SECONDS` (기본 900) |
| 리프레시 토큰 | 30일 (`doro.iam.jwt.refresh-token-validity-seconds`) |
| 세션 무활동 만료 | 30일, 갱신 때마다 연장 (`doro.iam.session.inactivity-timeout-seconds`) |
| 2FA 임시 티켓 | 5분 |

---

### 1.9 여러 계정 전환

**서버에는 "여러 계정" API가 없습니다.** (`/auth/accounts/add`, `/accounts/switch/{uidx}` 같은 엔드포인트는 존재하지 않습니다.) 다중 계정은 **포털(`web/`)의 클라이언트 기능**입니다.

- 계정마다 따로 로그인해 얻은 토큰을 브라우저 `localStorage`(`doro_auth_accounts`, `doro_active_account_index`)에 모아 두고, 어느 것을 쓸지만 바꿉니다.
- 서버 입장에서는 계정마다 독립된 일반 세션입니다. 토큰의 `uidx`는 "그 사용자의 활성 세션 순번"일 뿐 계정 전환 번호가 아닙니다.
- 포털의 "모든 계정에서 로그아웃"은 저장된 **각 계정의 토큰으로** 서버 세션을 하나씩 종료합니다.

---

## 2. OAuth 2.1 과 OIDC

### 2.1 개요

Doro IAM은 **인가 코드 + PKCE(S256)** 방식의 OAuth 2.1 / OpenID Connect 공급자입니다. 다른 서비스가 비밀번호를 직접 받지 않고 브라우저를 IAM으로 보내 로그인시킨 뒤 토큰을 받는 "리다이렉트 방식 로그인"에 씁니다.

> 지금 doro-blog 같은 서비스는 이 방식이 아니라 "인앱 로그인"(같은 IAM API를 같은 origin으로 호출)을 씁니다. OAuth는 **클라이언트를 등록하기 전에는 동작하지 않습니다**.

**흐름**

1. (한 번) 관리자가 클라이언트를 등록한다 → `client_id`, 허용 `redirect_uri` 목록.
2. 클라이언트 앱이 PKCE `code_verifier`/`code_challenge`, `state`, `nonce`를 만들고 브라우저를 `GET /oauth2/authorize?...`(Bearer 없음)로 보낸다.
3. IAM이 `client_id`/`redirect_uri`를 검증하고 **`302 Location: /oauth2/consent?<원래 쿼리 그대로>`** 로 동의 화면(포털)에 보낸다.
4. 사용자가 포털에서 로그인하고 "승인"하면, 포털이 같은 파라미터로 `GET /oauth2/authorize`를 **Bearer와 함께** fetch해 `{code, state}` JSON을 받는다.
5. 포털이 브라우저를 `redirect_uri?code=...&state=...` 로 보낸다.
6. 클라이언트 앱이 `POST /oauth2/token`으로 코드를 `code_verifier`와 함께 보내 `access_token`, `refresh_token`, `id_token`을 받는다.

**지원 범위**: `response_type=code` 하나, grant는 `authorization_code`와 `refresh_token`, PKCE는 `S256`만, **공개 클라이언트만**(클라이언트 시크릿 없음, `token_endpoint_auth_methods_supported: ["none"]`), 스코프는 `openid` `profile` `email` 세 개.

---

### 2.2 클라이언트 등록

관리자 API입니다(ADMIN 역할 + Guard `system:doro#admin`). 요청 본문 검증은 Guard 판정 **뒤에** 하므로 권한 없는 호출자는 검증 오류로 정보를 얻지 못하고 `403`만 받습니다.

```bash
curl -s -X POST $IAM/api/v1/admin/oauth/clients \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Demo App","redirectUris":["http://localhost:5000/callback"],"scopes":["openid","profile","email"]}'
```
`201 Created`:
```json
{
  "success": true,
  "data": {
    "id": "0b5c1b7e-6f0e-4a54-8d52-0c3d61f0c2a1",
    "clientId": "kQ2m0v8Zr1sT4yH7uPaB3cXw9LdE5nGj",
    "name": "Demo App",
    "redirectUris": ["http://localhost:5000/callback"],
    "scopes": ["openid", "profile", "email"],
    "active": true,
    "createdAt": "2026-10-02T00:00:00Z"
  },
  "timestamp": "2026-10-02T00:00:00Z"
}
```

| 필드 | 규칙 |
|---|---|
| `name` | 필수, 최대 100자 |
| `redirectUris` | 필수, 1~10개, 중복 불가, 각 최대 500자 |
| `scopes` | 선택. 생략하면 `openid profile email` 전체. `openid`/`profile`/`email` 외의 값은 `400` |
| `clientId` | 선택. 생략하면 서버가 32자 url-safe 문자열을 생성. 직접 지정하면 `[A-Za-z0-9._~-]{1,100}`, 이미 있으면 `409 OAUTH_CLIENT_ALREADY_EXISTS` |

**`redirect_uri` 등록 규칙**: 절대 URI, **https만**(loopback `localhost` / `127.0.0.1` / `[::1]`에 한해 http 허용). 와일드카드(`*`), fragment(`#`), userinfo(`user@`), 공백/제어문자, 역슬래시, 경로의 `.` `..` `%2e` `%2f` 세그먼트는 거부됩니다. 쿼리와 포트는 허용됩니다.

```bash
curl -s $IAM/api/v1/admin/oauth/clients -H "Authorization: Bearer $ADMIN_TOKEN"                       # 목록(최신순)
curl -s -X DELETE $IAM/api/v1/admin/oauth/clients/$CLIENT_ID -H "Authorization: Bearer $ADMIN_TOKEN"  # 비활성화
```
삭제는 **소프트 삭제**(`active=false`)입니다. 이후 그 `client_id`의 인가/토큰 요청은 **모든 레지스트리 모드에서** 거부됩니다. 없는 `client_id`는 `404 OAUTH_CLIENT_NOT_FOUND`.

#### 레지스트리 모드 `DORO_OAUTH_CLIENT_REGISTRY_MODE` (기본 `WARN`)

| 모드 | 등록된 활성 클라이언트 | 등록되지 않은 `client_id` |
|---|---|---|
| `OFF` | 레지스트리를 아예 쓰지 않음(등록돼 있어도 무시) | 환경변수 허용 목록으로만 판정 |
| `WARN` (기본) | **자기 `redirect_uri`와 정확히 일치**해야 하고, 스코프는 자기 허용 스코프의 부분집합이어야 함 | 환경변수 허용 목록(`DORO_OAUTH_ALLOWED_REDIRECT_URIS`)으로 폴백 + 경고 로그 |
| `ENFORCE` | 위와 같음 | `invalid_client`로 거부 |

- `DORO_OAUTH_ALLOWED_REDIRECT_URIS`는 쉼표 구분, **문자열 정확 일치**, 비어 있으면(기본) 미등록 클라이언트는 전부 거부됩니다. 등록된 클라이언트에는 적용되지 않습니다.
- `redirect_uri`는 어느 모드에서도 **정규화/부분 일치/와일드카드 없이 문자열이 완전히 같아야** 합니다(끝 슬래시, 대소문자, 포트, 스킴이 다르면 거부).
- 비활성화된 클라이언트는 환경변수 허용 목록을 통해서도 되살아나지 않습니다.
- 운영에서는 `ENFORCE`를 권장합니다. 미등록 `client_id`를 허용하는 `WARN`은 전환 기간용입니다.

---

### 2.3 인가 요청

`GET /oauth2/authorize`

| 파라미터 | 필수 | 규칙 |
|---|---|---|
| `client_id` | 예 | `[A-Za-z0-9._~-]{1,100}` |
| `redirect_uri` | 예 | 등록된(또는 허용 목록의) 값과 정확히 일치 |
| `response_type` | 예 | `code` 만 |
| `code_challenge` | 예 | S256 챌린지: base64url **43자** |
| `code_challenge_method` | 아니오 | 주면 `S256` 이어야 함(생략 가능) |
| `scope` | 아니오 | 공백 구분, 최대 200자. **생략하면 스코프 없음**(→ `id_token`도 없음) |
| `state` | 아니오 | 최대 512자. 서버는 그대로 돌려줄 뿐 검증하지 않으니 **클라이언트가 돌아온 값을 확인**해야 함 |
| `nonce` | 아니오 | 최대 256자. `id_token`에 그대로 들어감 |

동작은 **Bearer 헤더 유무**로 갈립니다.

**A. Bearer 없음 (브라우저 이동)**
1. `client_id`/`redirect_uri`를 **먼저** 검증합니다. 실패하면 **리다이렉트하지 않고** `400` JSON 에러를 돌려줍니다(오픈 리다이렉트 방지).
2. 이후의 파라미터 오류(`response_type`, `code_challenge`, `scope` 등)는 `redirect_uri`로 `302` + `?error=...&error_description=...&state=...`(RFC 6749 §4.1.2.1)로 돌려보냅니다.
3. 문제가 없으면 `302 Location: /oauth2/consent?<원래 쿼리 그대로>` 입니다. 동의 URL은 `DORO_OAUTH_CONSENT_URL`(기본 `/oauth2/consent`, 상대 경로)로 바꿀 수 있습니다.

```bash
curl -si -G $IAM/oauth2/authorize \
  --data-urlencode "client_id=$CLIENT_ID" --data-urlencode "redirect_uri=$REDIRECT" \
  --data-urlencode "response_type=code" --data-urlencode "scope=openid profile email" \
  --data-urlencode "state=$STATE" --data-urlencode "nonce=$NONCE" \
  --data-urlencode "code_challenge=$CHALLENGE" --data-urlencode "code_challenge_method=S256" | grep -i '^location'
```
(`Cache-Control: no-store`가 함께 나갑니다. 이 모드는 IP당 토큰 엔드포인트와 같은 요청 제한 버킷을 씁니다.)

**B. Bearer 있음 (포털 동의 화면의 fetch)** — 사용자가 승인한 뒤 호출합니다. Bearer는 **일반 로그인 토큰**이어야 합니다(OAuth 액세스 토큰은 여기서 인정되지 않습니다).
```bash
curl -s -G $IAM/oauth2/authorize -H "Authorization: Bearer $USER_TOKEN" \
  --data-urlencode "client_id=$CLIENT_ID" --data-urlencode "redirect_uri=$REDIRECT" \
  --data-urlencode "response_type=code" --data-urlencode "scope=openid profile email" \
  --data-urlencode "state=$STATE" --data-urlencode "nonce=$NONCE" \
  --data-urlencode "code_challenge=$CHALLENGE" --data-urlencode "code_challenge_method=S256"
```
```json
{ "success": true, "data": { "code": "Yb8...(43자)", "state": "csrf-state-123" }, "timestamp": "2026-10-02T00:00:00Z" }
```
`state`를 안 보냈으면 `""` 입니다. 이 모드의 오류는 리다이렉트가 아니라 일반 에러 JSON입니다(예: `400 INVALID_INPUT`, Bearer가 잘못되면 `401`). 인가 코드는 32바이트 랜덤(base64url 43자)이고 **1회용, 기본 5분**(`DORO_OAUTH_CODE_TTL_SECONDS`)이며 `client_id`, `redirect_uri`, 코드 챌린지, 스코프, `nonce`에 묶입니다. 코드가 묶이는 사용자 세션이 이미 폐기됐으면 `401`입니다.

포털의 동의 화면은 `/oauth2/consent` 라우트입니다. 로그인 전이면 검증된 동의 요청을 보관했다가(10분) 로그인 뒤 이 화면으로 돌아오고, 승인하면 `redirect_uri?code=...&state=...`로 이동합니다.

---

### 2.4 토큰 엔드포인트

`POST /oauth2/token` 은 두 가지 형식을 받습니다. 둘 다 IP 요청 제한 버킷(10분 60회)에 걸리고, 응답에는 `Cache-Control: no-store`가 붙습니다.

| | 폼 (RFC 6749) | JSON (기존 계약) |
|---|---|---|
| Content-Type | `application/x-www-form-urlencoded` | `application/json` |
| 요청 필드 | snake_case: `grant_type`, `code`, `redirect_uri`, `client_id`, `code_verifier`, `refresh_token`, `scope` | camelCase: `grantType`, `code`, `redirectUri`, `clientId`, `codeVerifier`, `refreshToken`, `scope` |
| 성공 응답 | RFC 응답(봉투 없음) | `ApiResponse` 봉투 + `TokenResponse`(camelCase) |
| 오류 응답 | RFC `{"error","error_description"}` | 일반 에러 본문(`ErrorResponse`) |

그 밖의 Content-Type은 `415`. 폼 요청은 **쿼리스트링 파라미터를 거부**하고(본문으로만), 같은 이름을 두 번 보내도 `invalid_request` 입니다. JSON에 snake_case 필드를 보내면 `grantType` 등이 비어 `400 INVALID_INPUT_VALUE` 가 됩니다.

#### authorization_code 교환

```bash
curl -s -X POST $IAM/oauth2/token \
  --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT" --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "code_verifier=$VERIFIER"
```
```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 900,
  "refresh_token": "Zk3...",
  "scope": "openid profile email",
  "id_token": "eyJ..."
}
```
- 4개 값(`code`, `redirect_uri`, `client_id`, `code_verifier`)이 모두 필요합니다.
- `code_verifier`는 `[A-Za-z0-9._~-]{43,128}`, 서버는 `BASE64URL(SHA256(verifier)) == code_challenge`를 상수 시간으로 비교합니다.
- **코드는 어떤 검증이 실패하든 소비됩니다**(1회용). 틀린 `redirect_uri`/`client_id`/verifier로 한 번 시도하면 같은 코드로 다시 시도할 수 없습니다.
- `scope`가 있으면 응답에 그 값이 나오고, `openid`가 포함됐을 때만 `id_token`이 옵니다.
- 같은 사용자가 같은 클라이언트로 다시 교환하면 **그 클라이언트의 이전 OAuth 세션만** 교체됩니다. 다른 클라이언트의 세션과 일반 로그인 세션은 유지됩니다.

같은 요청의 JSON 변형:
```bash
curl -s -X POST $IAM/oauth2/token -H 'Content-Type: application/json' \
  -d "{\"grantType\":\"authorization_code\",\"code\":\"$CODE\",\"redirectUri\":\"$REDIRECT\",\"clientId\":\"$CLIENT_ID\",\"codeVerifier\":\"$VERIFIER\"}"
# {"success":true,"data":{"accessToken":"...","refreshToken":"...","tokenType":"Bearer","expiresIn":900,"sessionId":"...","userIndex":0,"idToken":"...","scope":"openid profile email"},"timestamp":"..."}
```

#### refresh_token grant

```bash
curl -s -X POST $IAM/oauth2/token \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "refresh_token=$REFRESH"
```
응답은 새 `access_token`/`refresh_token`(+`expires_in`, `token_type`)이고 `id_token`/`scope`는 없습니다. 일반 리프레시와 같은 **회전과 재사용 감지**가 적용됩니다(재사용이면 그 OAuth 세션이 종료됨). **그 `client_id`의 OAuth 세션에 속한 토큰만** 받습니다. 다른 클라이언트의 토큰이나 일반 로그인의 리프레시 토큰은 `invalid_grant`로 거부되고, 이때 토큰은 소모되지 않습니다. `scope`를 보내면 클라이언트 허용 스코프의 부분집합인지만 검사합니다.

#### 오류

폼 형식 오류의 `error` 값과 HTTP 상태:

| `error` | HTTP | 대표 원인 |
|---|---|---|
| `invalid_request` | 400 | 필수값 누락, 쿼리/중복 파라미터, `client_id` 형식 오류 |
| `invalid_client` | **401** | 등록되지 않은(ENFORCE)/비활성화된 클라이언트 |
| `invalid_grant` | 400 | 코드 무효/만료/이미 사용, `client_id`·`redirect_uri` 불일치, PKCE 실패, 리프레시 토큰 무효, 계정 사용 불가 |
| `unsupported_grant_type` | 400 | `authorization_code`/`refresh_token` 외의 grant |
| `invalid_scope` | 400 | 지원하지 않거나 클라이언트에 허용되지 않은 스코프 |

JSON 형식에서는 같은 오류가 `400`의 일반 에러 본문으로 나옵니다(`invalid_grant` → `code: "INVALID_TOKEN"`, 리프레시 재사용은 `TOKEN_REUSE_DETECTED`, 그 외는 `INVALID_INPUT`). JSON 형식에서는 `invalid_client`도 `401`이 아니라 `400` 입니다.

---

### 2.5 id_token 과 userinfo 와 discovery

#### id_token (스코프에 `openid`가 있을 때만)

RS256, 액세스 토큰과 같은 키(`kid`)로 서명되고 `GET /.well-known/jwks.json`으로 검증합니다. 수명은 액세스 토큰과 같습니다(기본 15분).

| 클레임 | 값 |
|---|---|
| `iss` | `DORO_IAM_ISSUER` |
| `sub` | 사용자 UUID |
| `aud` | `client_id` |
| `iat`, `exp` | 발급/만료 |
| `auth_time` | 사용자가 **포털에서 로그인한 세션의 생성 시각**(epoch 초) |
| `sid` | 세션 ID |
| `nonce` | 인가 요청에서 보낸 값(보냈을 때만) |
| `email`, `email_verified` | `email` 스코프일 때. `email_verified`는 항상 `false` |
| `name`, `picture` | `profile` 스코프일 때. `picture`는 프로필 이미지가 http(s) URL일 때만 |

`role`/`cid` 클레임은 없습니다. 클라이언트는 **서명, `iss`, `aud`(=자기 `client_id`), `exp`, `nonce`**를 확인해야 합니다.

#### userinfo `GET /oauth2/userinfo`

OAuth 액세스 토큰을 Bearer로 보냅니다. 스코프와 무관하게 아래를 돌려줍니다(봉투 없음).
```json
{ "sub": "3fa85f64-...", "email": "dev@example.com", "email_verified": false, "name": "Doro Dev", "picture": "https://example.com/me.png" }
```
`picture`는 http(s) URL일 때만 포함됩니다. 인증이 없으면 `401` + `WWW-Authenticate: Bearer`, 세션이 폐기됐으면 `401`.

#### discovery `GET /.well-known/openid-configuration`

`issuer`, `authorization_endpoint`, `token_endpoint`, `userinfo_endpoint`, `jwks_uri`, `response_types_supported: ["code"]`, `response_modes_supported: ["query"]`, `grant_types_supported: ["authorization_code","refresh_token"]`, `subject_types_supported: ["public"]`, `id_token_signing_alg_values_supported: ["RS256"]`, `scopes_supported: ["openid","profile","email"]`, `code_challenge_methods_supported: ["S256"]`, `token_endpoint_auth_methods_supported: ["none"]`, `claims_supported`를 돌려줍니다.

> 엔드포인트 URL은 모두 **`DORO_IAM_ISSUER` 값 + 경로**로 만들어집니다. 기본값 `https://auth.doro.local`은 실제로 접속되는 주소가 아니므로, discovery를 쓰는 클라이언트가 있다면 `DORO_IAM_ISSUER`를 공개 origin으로 설정하세요(SDK의 `doro.iam.issuer`와도 맞춰야 `issuer-validation`이 통과합니다). 운영의 실제 값은 확인하지 못했습니다 `(미검증)`.

---

### 2.6 처음부터 끝까지 curl 예제

전제: `curl`, `jq`, `openssl`. 로컬 `docker compose up -d` 상태. 관리자 계정이 필요합니다(`admin@example.com`을 가입한 뒤 [1.6](#16-관리자-api)의 방법으로 `ADMIN`/`SUPER_ADMIN` 역할을 주고 IAM을 재시작). 2FA는 꺼져 있다고 가정합니다.

```bash
#!/usr/bin/env bash
set -euo pipefail
IAM=http://localhost:8080
REDIRECT='http://localhost:5000/callback'     # 클라이언트 앱의 콜백 (loopback 은 http 허용)
PW='Password123!'

login() {  # login <email>  -> 액세스 토큰
  curl -s -X POST $IAM/api/v1/auth/login -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$PW\"}" | jq -r '.data.tokens.accessToken'
}

# 0. 일반 사용자 준비 (이미 있으면 409 가 나도 무시)
curl -s -X POST $IAM/api/v1/auth/signup -H 'Content-Type: application/json' \
  -d "{\"email\":\"user@example.com\",\"password\":\"$PW\",\"name\":\"Demo User\"}" > /dev/null || true

# 1. 관리자: 클라이언트 등록
ADMIN_TOKEN=$(login admin@example.com)
CLIENT_ID=$(curl -s -X POST $IAM/api/v1/admin/oauth/clients \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Demo App\",\"redirectUris\":[\"$REDIRECT\"]}" | jq -r '.data.clientId')
echo "client_id=$CLIENT_ID"

# 2. 클라이언트 앱: PKCE / state / nonce 생성
VERIFIER=$(openssl rand -base64 48 | tr -d '=+/\n' | cut -c1-64)                     # 43~128자, unreserved 문자만
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
STATE=$(openssl rand -hex 8); NONCE=$(openssl rand -hex 8)
PARAMS=(--data-urlencode "client_id=$CLIENT_ID" --data-urlencode "redirect_uri=$REDIRECT"
        --data-urlencode "response_type=code" --data-urlencode "scope=openid profile email"
        --data-urlencode "state=$STATE" --data-urlencode "nonce=$NONCE"
        --data-urlencode "code_challenge=$CHALLENGE" --data-urlencode "code_challenge_method=S256")

# 3. 브라우저 이동: Bearer 없이 호출하면 동의 페이지로 302
curl -si -G $IAM/oauth2/authorize "${PARAMS[@]}" | grep -i '^location'

# 4. 동의 화면(포털)이 하는 일: 로그인한 사용자의 Bearer 로 같은 파라미터를 호출 -> 코드
USER_TOKEN=$(login user@example.com)
CODE=$(curl -s -G $IAM/oauth2/authorize -H "Authorization: Bearer $USER_TOKEN" "${PARAMS[@]}" | jq -r '.data.code')
# (포털은 이 코드를 "$REDIRECT?code=$CODE&state=$STATE" 로 브라우저에 돌려보낸다. 앱은 state 가 같은지 확인한다.)

# 5. 클라이언트 앱(서버 측): 코드 -> 토큰
TOKENS=$(curl -s -X POST $IAM/oauth2/token \
  --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT" --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "code_verifier=$VERIFIER")
echo "$TOKENS" | jq 'del(.access_token, .refresh_token, .id_token)'
ACCESS=$(echo "$TOKENS" | jq -r .access_token); REFRESH=$(echo "$TOKENS" | jq -r .refresh_token); IDT=$(echo "$TOKENS" | jq -r .id_token)

# 6. id_token 클레임 확인 (로컬 디코드. 서명 검증은 JWKS 로 해야 하며 여기서는 내용만 본다)
echo "$IDT" | jq -R 'split(".")[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson'   # (미검증: jq 버전에 따라 패딩 오류 가능)

# 7. userinfo
curl -s $IAM/oauth2/userinfo -H "Authorization: Bearer $ACCESS" | jq .

# 8. 리프레시 (회전: 새 refresh_token 으로 교체해야 한다)
NEW=$(curl -s -X POST $IAM/oauth2/token \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "refresh_token=$REFRESH")
echo "$NEW" | jq 'keys'

# 9. 이전 리프레시 토큰을 다시 쓰면 재사용 감지 -> invalid_grant 이고 이 OAuth 세션이 종료된다
curl -s -X POST $IAM/oauth2/token \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "refresh_token=$REFRESH"
# {"error":"invalid_grant","error_description":"리프레시 토큰이 유효하지 않습니다."}

# 10. OAuth 액세스 토큰은 IAM 의 일반 API 에서 거부된다 (userinfo, sessions/current 에서만 인정)
curl -s -o /dev/null -w 'users/me          -> %{http_code}\n' $IAM/api/v1/users/me -H "Authorization: Bearer $ACCESS"      # 401
curl -s -o /dev/null -w 'sessions/current  -> %{http_code}\n' $IAM/api/v1/sessions/current -H "Authorization: Bearer $ACCESS"  # 204 또는 (위 9단계 이후) 401
```
9단계에서 세션이 종료되므로 10단계의 `sessions/current`는 `401`이 나올 수 있습니다. 확인만 하려면 9단계 전에 실행하세요.

---

### 2.7 보안 속성

**보장되는 것**

- **공개 클라이언트 + PKCE(S256) 필수.** 클라이언트 시크릿 인증은 없습니다.
- **`redirect_uri`는 정확 일치.** 검증 실패 시 절대 `redirect_uri`로 리다이렉트하지 않습니다(`client_id`/`redirect_uri` 검증이 다른 모든 파라미터 검증보다 먼저).
- **인가 코드는 1회용이고 검증 실패 시에도 소비**됩니다. 저장소에는 코드 원문이 아니라 SHA-256 해시만 둡니다.
- **OAuth 액세스 토큰은 `cid=<client_id>`를 갖고 `role`은 항상 `USER`.** 관리자가 OAuth로 로그인해도 클라이언트로 관리자 권한이 넘어가지 않습니다.
- **IAM은 OAuth 액세스 토큰(`cid` 있음)을 `GET /oauth2/userinfo`와 `GET /api/v1/sessions/current`에서만 인증으로 인정**합니다. 프로필·세션 목록·로그아웃·2FA·관리자 등 다른 IAM API에서는 `401`입니다.
- **id_token(`aud` 있음)은 IAM의 어떤 API에서도 액세스 토큰으로 인정되지 않습니다.** (`userinfo`도 id_token이면 `401`.) SDK도 `doro.iam.audience`를 설정하지 않으면 `aud`가 있는 토큰을 인증으로 받지 않습니다([4.3](#43-필터가-하는-일과-하지-않는-일)).
- OAuth 세션은 클라이언트별로 만들어져, 같은 클라이언트의 이전 세션만 교체됩니다. 리프레시 토큰은 해당 클라이언트의 세션에만 쓸 수 있고, **일반 갱신 엔드포인트(`POST /api/v1/auth/token/refresh`)는 OAuth 세션의 리프레시 토큰을 거부**합니다(회전 전에 확인하므로 정상 클라이언트의 토큰이 소모되지도 않음). 그래서 OAuth 클라이언트가 사용자의 실제 권한이 담긴 일반 로그인 토큰을 얻을 수 없습니다.
- 토큰 엔드포인트와 Bearer 없는 인가 요청에는 IP 단위 요청 제한이 있습니다.

**연동 시 알아 둘 점**

- **서브 서비스에서 OAuth 액세스 토큰은 `role=USER` 사용자의 일반 토큰처럼 쓰입니다.** SDK는 `cid`/스코프를 보지 않으므로, 서비스는 `role` 클레임이 아니라 **Guard로 권한을 판정**하세요(이 가이드의 다른 곳에서도 같은 원칙입니다).
- **스코프는 `id_token` 클레임을 정합니다.** 액세스 토큰에는 스코프가 실리지 않고, `userinfo`는 스코프와 무관하게 `sub`/`email`/`name`/`picture`를 돌려줍니다.
- **`state`는 클라이언트가 직접 대조**하세요(서버는 그대로 되돌려 줄 뿐 검증하지 않습니다).

---

### 2.8 설정과 운영

| 환경변수 | 기본 | 설명 |
|---|---|---|
| `DORO_OAUTH_CLIENT_REGISTRY_MODE` | `WARN` | `OFF` / `WARN` / `ENFORCE` |
| `DORO_OAUTH_ALLOWED_REDIRECT_URIS` | 비어 있음 | 미등록 클라이언트용 `redirect_uri` 정확 일치 허용 목록(쉼표 구분) |
| `DORO_OAUTH_CONSENT_URL` | `/oauth2/consent` | Bearer 없는 인가 요청을 보낼 동의 페이지(원래 쿼리가 그대로 붙음) |
| `DORO_OAUTH_CODE_STORE` | `auto` | 인가 코드 저장소: `auto`(Redis에 닿으면 Redis, 아니면 메모리) / `redis` / `memory` |
| `DORO_OAUTH_CODE_TTL_SECONDS` | `300` | 인가 코드 수명 |
| `DORO_OAUTH_MAX_PENDING_AUTHORIZATION_CODES` | `10000` | 미사용 코드 상한(메모리 저장소). 초과하면 발급이 `429` |
| `DORO_OAUTH_WARN_LOG_INTERVAL_SECONDS` | `60` | 재사용 코드/미등록 클라이언트 경고 로그의 최소 간격 |
| `DORO_IAM_RATE_LIMIT_TOKEN_MAX` | `60` | 토큰 엔드포인트(및 Bearer 없는 인가) IP당 10분 요청 수 |
| `DORO_CORS_ALLOWED_ORIGIN_PATTERNS` | 로컬 + 운영 도메인 목록 | 브라우저에서 IAM을 직접 호출할 수 있는 Origin 패턴 |

- **코드 저장소**: Redis에서는 `doro:oauth:code:<sha256>` 키에 TTL로 보관하고, 사용된 코드의 해시는 `doro:oauth:code-used:` 키에 코드 TTL 동안 남겨 재사용을 탐지합니다. `auto`는 **기동 시점**에 Redis가 닿지 않으면 그 프로세스가 끝날 때까지 메모리를 씁니다(재시작/다중 인스턴스에서 코드가 유실됩니다).
- **CORS**: 클라이언트 앱의 프런트가 다른 origin에서 `POST /oauth2/token`을 브라우저로 직접 호출하면 그 origin이 `DORO_CORS_ALLOWED_ORIGIN_PATTERNS`에 있어야 합니다. 값은 쉼표 구분 패턴이고 `*` 단독은 기동 시 거부됩니다. 허용 헤더는 `Authorization`, `Content-Type`, `Accept`, `X-Trace-Id`, `X-Requested-With`, 자격증명(쿠키/Authorization) 허용입니다. 서버 측에서 토큰을 교환하는 앱에는 필요 없습니다.
- 게이트웨이의 `/oauth2/consent`(포털로) 및 `/oauth2/`(IAM으로) 라우트는 `gateway/nginx.conf`에 있고, 서버에 반영하려면 수동으로 복사해야 합니다(게이트웨이는 compose 밖).

---

## 3. Doro Guard

구글 Zanzibar 모델을 줄 단위 DSL과 함께 구현한 관계 기반 인가(ReBAC) 엔진입니다. "누가(subject)가 무엇(object)에 어떤 관계(relation)인가"를 **튜플**로 저장하고, **스키마 규칙**에 따라 권한을 계산합니다. REST는 `/api/v1/guard/**`(8081), gRPC는 9090(평문)입니다. REST 응답은 IAM과 같은 `{success, data, timestamp}` 봉투입니다.

### 3.1 개념과 표기법

```
 namespace:objectId#relation @ subjectNamespace:subjectId[#subjectRelation]

 document:d1#owner@user:alice            alice 는 d1 의 owner (직접 튜플)
 document:d1#viewer@group:eng#member     group:eng 의 member 전원이 d1 의 viewer (userset 튜플)
 document:d1#parent@folder:f1            d1 의 부모는 f1 (TTU 의 연결 고리)
```
- **튜플**은 `relation_tuples` 테이블에 저장됩니다. 같은 튜플을 다시 쓰면 무시됩니다(멱등).
- **스키마**는 타입별 릴레이션 규칙이며, 활성 스키마는 **Guard 전체에 하나**입니다. IAM의 `system`/`user` 타입과 모든 서비스(`blog_post` 등)의 타입이 같은 스키마 안에 있습니다.
- 사용자 `subjectNamespace`는 관례상 `user`, `subjectId`는 IAM 사용자 UUID(JWT `sub`)입니다.

### 3.2 스키마 DSL

```text
# 전체 줄 주석 (# 또는 //)
type blog_post {
  relation author: user
  relation series: blog_series
  relation editor: author
  relation viewer: author | user
}
```

**정확한 파서 규칙** (`DslParser`)

| 규칙 | 설명 |
|---|---|
| 줄 단위 | 각 줄을 trim 합니다. 빈 줄, `#` 또는 `//`로 **시작하는 줄**은 건너뜁니다. **줄 끝 주석은 지원하지 않습니다**(식의 일부가 되어 오동작) |
| 타입 | `type 이름 {` ... 닫는 `}`(독립된 줄). `type 이름`만 쓰고 다음 줄에 `{`만 둬도, `type 이름 {}` 도 됩니다. 같은 이름의 `type`을 두 번 쓰면 뒤의 것이 앞의 것을 통째로 대체합니다 |
| 릴레이션 | `relation 이름: 식` **한 줄**. 첫 `:` 앞이 이름입니다 |
| 합집합 `\|` | 공백 불필요 (`a\|b` 가능) |
| 교집합 `&` | **양옆 공백 필수** (` & `). 항은 단일 항만 올 수 있음(안에 `\|` 불가) |
| 차집합 `-` | **양옆 공백 필수** (` - `). 우결합: `a - b - c` = `a - (b - c)` |
| 우선순위 | ` - `(가장 낮음) → ` & ` → `\|`. 예: `a \| b - c` = `(a \| b) - c` |
| `x#y` 항 | **항상 TTU**: "이 객체의 `x` 릴레이션 튜플이 가리키는 객체의 `y`" |
| 같은 타입의 선언된 릴레이션 이름 | 그 릴레이션을 참조(계산 릴레이션). **이미 위에서 선언된 것만** 인식합니다(선언 순서!) |
| 그 외의 이름 (`user`, `group` 등) | **직접 항(inert)**: 평가에 아무 영향이 없습니다. 문서 역할만 합니다 |
| 미지원 | **괄호**, `&`와 `\|`의 혼용, 줄 끝 주석, 타입 제약 강제(`relation owner: user`의 `user`는 선언일 뿐) |

**꼭 알아야 할 결과들**

1. **선언 순서**: `relation viewer: user | editor` 를 `relation editor: ...` **보다 먼저** 쓰면 `editor`는 계산 릴레이션이 아니라 직접 항으로 취급되어 **항상 false**입니다. 사용하는 릴레이션은 위에서 먼저 선언하세요.
2. **직접 항은 inert**: `relation viewer: user` 라고 써도 "user 타입만 허용" 같은 제약은 없습니다. 판정은 아래 [Check 알고리즘](#34-check-api)의 1단계(직접 튜플 일치)에서 이미 끝납니다.
3. **`group#member`는 TTU로 읽힙니다.** 즉 `relation editor: user | group#member | owner`의 `group#member`는 "이 객체에 `group`이라는 릴레이션의 튜플이 있으면…"이란 뜻이라, 보통 아무 튜플도 없어 비활성입니다. **그룹 멤버십을 부여하는 실제 방법은 userset 튜플**(`document:d1#viewer@group:eng#member`)입니다. DSL에 `group#member`를 써 두는 것은 의도를 알리는 문서 역할이고, 기본 스키마도 이렇게 씁니다.
4. 평가용 AST는 항을 위처럼 해석하므로, **의도와 다르게 파싱돼도 오류가 나지 않습니다.** 등록 후 `check`로 확인하세요.

**검증 모드** `DORO_GUARD_VALIDATION_MODE` (기본 `WARN`)

스키마를 등록할 때 파서가 두 종류의 **진단**(줄 번호 포함)을 만듭니다.
- (a) 이해하지 못해 무시한 줄(예: 오타 난 키워드 `relashun x: user`). 주석, 빈 줄, 단독 `{`는 제외.
- (b) **선언되지 않은 타입 이름을 가리키는 직접 항** — 오타(`usr`)나 **전방 참조**(뒤에 선언된 릴레이션을 먼저 사용)가 여기에 걸립니다. TTU 항(`parent#viewer`)과 계산 릴레이션 항은 검사하지 않습니다. `a | b & c` 처럼 지원하지 않는 혼용도 `a | b` 라는 이름의 직접 항으로 보여 (b)에 걸립니다.

| 모드 | 스키마 등록 | 튜플 쓰기 |
|---|---|---|
| `OFF` | 진단 무시 | 검사 안 함 |
| `WARN` (기본) | 경고 로그만 남기고 **등록됨** | 스키마에 없는 타입/릴레이션이어도 로그만 남기고 **써짐** |
| `ENFORCE` | `400 INVALID_SYNTAX` (`"스키마 검증 실패: line N: ..."`)로 거부 | 배치 전체를 `400 INVALID_TUPLE`로 거부(아무것도 안 써짐) |

문법 자체의 오류는 **모드와 상관없이 항상** `400 INVALID_SYNTAX`입니다: 타입 블록 밖의 `relation`, `relation`에 `:` 없음, `type`에 이름 없음. 기본값이 `WARN`이므로 **전방 참조 같은 실수가 에러 없이 등록**된다는 점에 주의하세요.

튜플 검증은 `namespace`/`relation`이 활성 스키마에 선언돼 있는지, 그리고 `subjectRelation`이 있으면 그 `subjectNamespace` 타입에 그 릴레이션이 선언돼 있는지를 봅니다.

---

### 3.3 튜플 API

본문은 **JSON 배열**입니다(객체로 감싸지 않습니다). 필드는 camelCase, `subjectRelation`만 선택입니다.

```bash
# 쓰기
curl -s -X POST $GUARD/api/v1/guard/tuples -H 'Content-Type: application/json' -d '[
  {"namespace":"document","objectId":"d1","relation":"owner","subjectNamespace":"user","subjectId":"alice"},
  {"namespace":"document","objectId":"d1","relation":"viewer","subjectNamespace":"group","subjectId":"eng","subjectRelation":"member"},
  {"namespace":"group","objectId":"eng","relation":"member","subjectNamespace":"user","subjectId":"bob"}
]'
# {"success":true,"data":{"writtenCount":3},"timestamp":"..."}

# 삭제 (본문이 있는 DELETE, 같은 배열 형식)
curl -s -X DELETE $GUARD/api/v1/guard/tuples -H 'Content-Type: application/json' -d '[
  {"namespace":"group","objectId":"eng","relation":"member","subjectNamespace":"user","subjectId":"bob"}
]'
# {"success":true,"data":{"deletedCount":1},"timestamp":"..."}
```
- `writtenCount`는 **실제로 새로 삽입된 행 수**입니다. 이미 있던 튜플이나 같은 배치 안의 중복은 세지 않습니다(그래서 같은 요청을 다시 보내면 0).
- `deletedCount`는 실제로 삭제된 행 수이고, 없는 튜플을 지우는 것은 오류가 아니라 0입니다.
- 모드가 `OFF`가 아니면 `X-Doro-Service-Token` 헤더가 필요합니다([3.7](#37-서비스-토큰)).
- 튜플이 실제로 바뀌면 커밋 **후** 인가 캐시를 비웁니다.
- 필드 길이 제한은 [3.8](#38-입력-제한과-에러-응답)을 보세요.

---

### 3.4 Check API

```bash
curl -s -X POST $GUARD/api/v1/guard/check -H 'Content-Type: application/json' -d '{
  "namespace":"document","objectId":"d1","relation":"viewer",
  "subjectNamespace":"user","subjectId":"alice"
}'
```
```json
{ "success": true, "data": { "allowed": true, "depth": 2, "reason": "ACCESS_GRANTED" }, "timestamp": "2026-10-02T00:00:00Z" }
```
요청 필드는 `namespace`, `objectId`, `relation`, `subjectNamespace`, `subjectId`(모두 필수)와 선택 `subjectRelation`입니다. **REST에는 `subjectNamespace` 기본값이 없습니다**(SDK는 `user`를 기본으로 넣어 줍니다).

| 응답 필드 | 의미 |
|---|---|
| `allowed` | 허용 여부 |
| `depth` | 평가 중 실제로 도달한 **최대 재귀 깊이**(직접 튜플은 0, 계산 릴레이션/TTU 한 단계마다 +1). 거부 결과에도 탐색한 깊이가 나옵니다 |
| `reason` | `ACCESS_GRANTED` / `ACCESS_DENIED` / `L1_CACHE_HIT`(캐시에서 나옴. `allowed`·`depth`는 캐시된 값) |

위 예제 값은 코드와 기본 스키마(`document`)로 유도한 것입니다(alice: `viewer` → `editor` → `owner`로 2단계). 그룹 멤버 `bob`의 `viewer` 판정은 userset 튜플을 따라 `depth: 1`이 되고, 아무 튜플도 없는 사용자는 `allowed:false`(`ACCESS_DENIED`)입니다.

**판정 알고리즘** (`CheckEngine`)

1. **직접 튜플** 일치 → 허용. 스키마를 보지 않으므로 스키마에 없는 타입/릴레이션도 튜플이 있으면 일치합니다.
2. **userset 튜플 따라가기**: 그 객체#릴레이션의 튜플 중 `subjectRelation`이 있는 것(예: `group:eng#member`)마다 "요청 주체가 그 userset의 구성원인가"를 재귀로 확인합니다.
3. **스키마 규칙 평가**: 합집합/교집합/차집합, 계산 릴레이션, TTU. 스키마나 타입/릴레이션이 없으면 거부합니다.
4. **안전장치**: 최대 깊이 `doro.guard.engine.max-depth`(32), 경로별 순환 차단. 잘리거나 순환이면 그 가지는 `false`이고, **차집합의 빼는 쪽이 잘리면 전체를 거부**(fail-closed)하며, 잘린 결과는 캐시하지 않습니다. 예외가 허용으로 이어지지는 않습니다.
5. **캐시**: Caffeine L1, 허용/거부 모두 캐시. `DORO_GUARD_CACHE_TTL_SECONDS`(기본 60, 0 이하면 끔), `DORO_GUARD_CACHE_MAX_SIZE`(기본 50000). 튜플 변경(커밋 후)과 스키마 변경 시 전체 무효화됩니다. 인스턴스 로컬 캐시라 다른 인스턴스의 변경은 최대 TTL만큼 늦게 보입니다.

---

### 3.5 스키마 조회와 등록

#### 조회 `GET /api/v1/guard/schema`

`data`가 **DSL 문자열 하나**입니다(개행은 `\n`으로 이스케이프됨).
```bash
curl -s $GUARD/api/v1/guard/schema | jq -r .data      # 사람이 읽는 DSL 로 출력
```
DB에 활성 버전이 없으면 클래스패스 기본 스키마(`schema.doro`)가 나옵니다.

#### 등록 `POST /api/v1/guard/schema`

본문은 **JSON `{"dsl": "..."}`** 입니다(`text/plain` 업로드는 `415`).
```json
{ "success": true, "data": { "version": 3, "dsl": "type user {...}", "active": true }, "timestamp": "..." }
```
- 새 버전이 만들어지고 이전 버전은 `is_active=false`로 DB에 남지만, **롤백 API는 없습니다.**
- 같은 시각의 동시 등록으로 버전이 충돌하면 최대 3회 재시도하고, 그래도 안 되면 `409 SCHEMA_CONFLICT`입니다.
- 메모리 반영은 커밋 후이고, 다른 Guard 인스턴스는 `DORO_GUARD_SCHEMA_REFRESH_SECONDS`(기본 30초, 0이면 끔)마다 DB의 활성 버전을 확인해 **더 높은 버전만** 적용합니다.

> ### ⚠ 등록은 "전체 교체"입니다
> 보낸 DSL이 활성 스키마를 **통째로 대체**합니다. **자기 서비스의 타입만 보내면 IAM의 `system`/`user` 타입과 다른 서비스의 타입이 사라지고**, 그 순간부터 관리자 판정과 다른 서비스의 권한 규칙이 깨집니다. 반드시 **GET → 병합 → POST** 순서로 하세요.

병합 절차(셸 버전):
```bash
H=(-H "X-Doro-Service-Token: $GUARD_TOKEN")        # Guard 보안 모드가 OFF 면 이 줄과 ${H[@]} 를 빼세요
CUR=$(curl -s "${H[@]}" $GUARD/api/v1/guard/schema | jq -r .data)
printf '%s\n' "$CUR" > schema-backup-$(date +%Y%m%d%H%M%S).doro                 # 항상 백업
if printf '%s' "$CUR" | grep -q 'type blog_post'; then echo "이미 등록됨"; exit 0; fi   # 멱등성 확인
printf '%s\n\n%s\n' "$CUR" "$(cat blog-schema.doro)" > merged.doro
jq -n --rawfile dsl merged.doro '{dsl:$dsl}' | \
  curl -s -X POST "${H[@]}" -H 'Content-Type: application/json' --data @- $GUARD/api/v1/guard/schema
```
등록 후에는 **의도한 대로 동작하는지 `check`로 확인**하세요(특히 WARN 모드에서는 오타가 조용히 통과합니다). 서비스 코드에서 하는 같은 절차는 [5단계 블루프린트](#5-새-서브-서비스-블루프린트)의 `BlogSchemaInitializer` 예제입니다.

**권한**: Guard 보안 모드가 켜져 있으면(`WARN`/`ENFORCE`) 스키마 쓰기는 **`schema-write` 권한이 있는 호출자 토큰**이어야 합니다. `ENFORCE`에서만 실제로 `403`을 반환하고, `WARN`에서는 로그만 남깁니다. `OFF`에서는 누구나 쓸 수 있습니다. ([3.7](#37-서비스-토큰))

---

### 3.6 gRPC 와 Expand

`doro.guard.v1.GuardService`, 포트 9090, **평문(TLS 없음)**. 서비스 SDK는 gRPC를 씁니다. 정의: [`guard/src/main/proto/doro_guard.proto`](../guard/src/main/proto/doro_guard.proto) (`sdk/`에 같은 파일의 사본이 있음)

| RPC | 요청 | 응답 |
|---|---|---|
| `Check` | `namespace, object_id, relation, subject_namespace, subject_id, subject_relation` | `allowed, depth, reason` |
| `WriteTuples` | `repeated RelationTupleProto tuples` | `written_count` |
| `DeleteTuples` | `repeated RelationTupleProto tuples` | `deleted_count` |
| `Expand` | `namespace, object_id, relation` | `tree_json` (JSON 문자열) |

- 서비스 토큰은 gRPC 메타데이터 `x-doro-service-token`으로 보냅니다. 없거나 틀리면 `ENFORCE`에서 `UNAUTHENTICATED`.
- 입력 오류(빈 값, 길이 초과, `ENFORCE`의 `INVALID_TUPLE`)는 `INVALID_ARGUMENT`, 그 밖의 서버 오류는 `INTERNAL`입니다.
- 서버 리플렉션은 켜져 있지 않아 `grpcurl`은 proto 파일을 지정해야 합니다 `(미검증: 예시)`:
  ```bash
  grpcurl -plaintext -import-path guard/src/main/proto -proto doro_guard.proto \
    -d '{"namespace":"document","objectId":"d1","relation":"viewer","subjectNamespace":"user","subjectId":"alice"}' \
    localhost:9090 doro.guard.v1.GuardService/Check
  ```
- **Expand는 gRPC로만 제공**됩니다(REST 엔드포인트 없음).

**Expand 트리** — `object#relation`에 접근할 수 있는 주체를 트리로 돌려줍니다.
```json
{
  "object": "folder:root#editor",
  "type": "union",
  "subjects": [],
  "children": [
    { "object": "folder:root#owner", "type": "computed", "subjects": [],
      "children": [ { "object": "folder:root#owner", "type": "leaf", "subjects": ["user:david"], "children": [] } ] }
  ]
}
```
(기본 스키마에서 `folder:root#owner@user:david` 튜플만 있을 때 `folder:root#editor`를 전개한 결과를 코드로 유도한 예입니다.)

| 필드 | 설명 |
|---|---|
| `object` | `ns:id#relation` |
| `type` | `leaf` / `union` / `intersection` / `difference` / `computed` / `ttu` |
| `subjects` | 이 노드의 **직접 튜플** 주체들(`user:david`, userset이면 `group:eng#member`). 사전순 |
| `children` | userset 주체, 계산 릴레이션, TTU로 펼친 하위 노드 |
| `truncated` | **루트에만**, 깊이(`max-depth` 32)나 노드 수 상한(`DORO_GUARD_EXPAND_MAX_NODES`, 기본 5000)에 걸려 잘렸을 때 `true` |

SDK에서는 `DoroGuardClient.expand(...)`(실패 시 `null`) / `expandOrThrow(...)`로 이 JSON 문자열을 받습니다.

---

### 3.7 서비스 토큰

Guard에는 사용자 로그인이 없습니다. 호출하는 **서비스**를 `X-Doro-Service-Token` 헤더(gRPC는 `x-doro-service-token` 메타데이터)로 식별합니다. 대상은 REST `/api/v1/guard/**`와 gRPC 전체입니다(헬스체크/Swagger/actuator는 제외).

| `DORO_GUARD_SECURITY_MODE` | 동작 |
|---|---|
| `OFF` (기본) | 검사하지 않음 — **누구나 읽고 쓸 수 있음** |
| `WARN` | 토큰이 없거나 틀리면 경고 로그만 남기고 통과 |
| `ENFORCE` | 없거나 틀리면 REST `401`, gRPC `UNAUTHENTICATED` |

- **호출자별 토큰** `DORO_GUARD_SERVICE_TOKENS`: `이름:토큰[:권한+권한]`을 쉼표로 이어 씁니다.
  ```
  DORO_GUARD_SERVICE_TOKENS=auth:<32자 이상 임의 문자열>,blog:<32자 이상 임의 문자열>:schema-write
  ```
  이름은 `[a-z][a-z0-9-]{0,31}`(`shared`는 예약), 토큰은 **32자 이상**이고 서로 달라야 합니다(토큰에 `:` `,`를 쓰면 안 됩니다). 형식 오류/짧은 토큰/중복/알 수 없는 권한은 **Guard 기동 시점에 실패**합니다.
- **권한(scope)**: 현재 `schema-write` 하나. 스키마를 바꾸는 요청(`GET`/`HEAD`/`OPTIONS`가 아닌 `/api/v1/guard/schema`)에만 필요하고, 조회/체크/튜플 쓰기에는 필요 없습니다. 보통 스키마를 등록하는 서비스(blog)만 갖고 IAM(auth)은 갖지 않습니다.
- **공유 토큰** `DORO_GUARD_SERVICE_TOKEN`: 모든 호출자가 같이 쓰는 이전 방식이며 **모든 권한**을 가집니다. 호출자별 토큰이 설정된 뒤 공유 토큰으로 오는 호출은 경고 로그에 남습니다(정리 시점 판단용).
- 토큰 비교는 상수 시간이고 토큰 값은 오류/로그에 남지 않습니다.
- **호출자 쪽 설정**: SDK는 `doro.guard.service-token`, IAM은 `DORO_GUARD_SERVICE_TOKEN`(compose는 `DORO_GUARD_AUTH_TOKEN`이 있으면 그것) 값을 헤더로 보냅니다.
- 오류 본문(보안 필터가 직접 씀, 봉투 형식도 에러 본문 형식도 아님):
  `401 {"success":false,"code":"UNAUTHORIZED","message":"서비스 인증이 필요합니다.","status":401}` ,
  `403 {"success":false,"code":"FORBIDDEN","message":"스키마를 변경할 권한이 없습니다.","status":403}`

**올리는 순서**: 모든 호출자가 토큰을 보내도록 배포한 뒤 `OFF → WARN → ENFORCE`로 올립니다. 로그(`Guard REST call without a valid service token` 등)가 더 안 나오는지 확인하고 `ENFORCE`로 갑니다. 운영 서버용 보조 스크립트가 있습니다: [`scripts/set-guard-mode.sh`](../scripts/set-guard-mode.sh), [`scripts/split-guard-tokens.sh`](../scripts/split-guard-tokens.sh) (사용법은 각 스크립트 머리말).

> `ENFORCE`에서 토큰이 없는 서비스의 SDK `check`는 `false`를 돌려주므로 서비스에서는 **`403`**으로 보입니다. [6장](#6-문제-해결)을 보세요.

---

### 3.8 입력 제한과 에러 응답

**필드 길이** (DB 컬럼과 같음, REST와 gRPC 동일 기준): `namespace`/`relation`/`subjectNamespace`/`subjectRelation` 최대 **64자**, `objectId`/`subjectId` 최대 **128자**. 필수 필드는 비어 있을 수 없습니다. (`objectId`에 UUID를 쓰면 36자라 문제없습니다.)

**에러 본문**은 IAM과 같은 형식입니다(`timestamp`, `status`, `error`, `code`, `message`, `path`, `details?`).

| HTTP | `code` | 언제 |
|---|---|---|
| 400 | `INVALID_INPUT_VALUE` | 필드 검증 실패(`details`에 필드별 사유), 본문 형식 오류(배열 대신 객체 등) |
| 400 | `INVALID_INPUT` | gRPC의 필드 검증 위반(비어 있음/길이 초과). gRPC에서는 `INVALID_ARGUMENT`로 변환되어 나가고, REST는 위의 `INVALID_INPUT_VALUE`가 대신 나옵니다 |
| 400 | `INVALID_SYNTAX` | 스키마 DSL 문법 오류 또는 `ENFORCE`의 스키마 검증 실패 |
| 400 | `INVALID_TUPLE` | `ENFORCE`에서 스키마에 없는 타입/릴레이션 튜플 |
| 409 | `SCHEMA_CONFLICT` | 스키마 버전 충돌이 반복됨(잠시 후 재시도) |
| 401/403 | `UNAUTHORIZED`/`FORBIDDEN` | 서비스 토큰 (위 형식) |
| 404/405/415 | `NOT_FOUND`/`METHOD_NOT_ALLOWED`/`UNSUPPORTED_MEDIA_TYPE` | |
| 500 | `INTERNAL_SERVER_ERROR` | |

---

### 3.9 기본 스키마

[`guard/src/main/resources/schema.doro`](../guard/src/main/resources/schema.doro)가 DB에 활성 스키마가 없을 때의 기본값입니다.

| 타입 | 릴레이션 |
|---|---|
| `user` | `manager: user \| system#admin`, `super_manager: user \| system#super_admin`, `can_reset_2fa: manager \| super_manager` |
| `group` | `member: user \| group#member` |
| `system` | `super_admin: user`, `admin: user \| super_admin`, `auditor: user \| admin`, `manage_roles: super_admin` |
| `folder` | `parent`, `owner`, `editor: user \| owner`, `viewer: user \| editor \| parent#viewer` |
| `document` | `parent`, `owner`, `editor: user \| group#member \| owner`, `viewer: user \| group#member \| editor \| parent#viewer` |

`user`/`system` 타입은 IAM이 관리자 판정에 씁니다([1.6](#16-관리자-api)). **이 타입들을 지우거나 바꾸는 스키마를 등록하지 마세요.** 모든 규칙은 앞에서 말한 "먼저 선언된 릴레이션만 참조" 순서를 지키고 있습니다.

---

## 4. Doro SDK

서브 서비스가 인증·인가를 위임받는 **Spring Boot 자동 구성 라이브러리**입니다. JWT를 **IAM 호출 없이 로컬에서** 검증하고(JWKS 공개키), `@DoroGuard`로 Guard에 인가를 위임합니다. Spring Security와 무관하게 동작하며(SDK는 `SecurityContext`를 채우지 않음), 기동 시 `META-INF/spring/...AutoConfiguration.imports`로 자동 등록됩니다.

### 4.1 의존성

SDK는 공개 저장소에 **배포되어 있지 않습니다.** 다른 저장소에서는 Gradle **composite build**로 소스를 직접 치환해 씁니다(실사용 예: `doro-blog`).

```groovy
// settings.gradle  (Doro 저장소가 ../Doro 에 체크아웃되어 있어야 합니다)
rootProject.name = 'my-service'
includeBuild('../Doro') {
    dependencySubstitution {
        substitute module('com.hunnit-beasts:doro-sdk') using project(':sdk')
    }
}
```
```groovy
// build.gradle
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }          // Java 25
tasks.withType(JavaCompile).configureEach { options.compilerArgs << '-parameters' }   // 필수: SpEL 이 파라미터 이름을 쓰려면
dependencies {
    implementation 'com.hunnit-beasts:doro-sdk'
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```
- Spring Boot 4.1.x, Java 25가 전제입니다. SDK는 gRPC/protobuf, JJWT, AOP를 `api`로 함께 가져옵니다.
- `-parameters`가 없으면 `#postId` 같은 SpEL이 파라미터 이름을 못 찾습니다(대안: `#p0`, `#a0`, `#args[0]`).
- Doro 모노레포 안의 모듈이라면 `implementation project(':sdk')`도 됩니다.
- Docker 이미지는 보통 **미리 빌드한 jar를 복사**합니다(doro-blog의 `Dockerfile`처럼). composite build의 Doro 소스는 jar를 빌드할 때만 필요합니다.

### 4.2 설정 속성 전체표

`application.yaml`에서 `doro.*`로 설정합니다. (`DoroProperties`)

| 속성 | 기본값 | 설명 |
|---|---|---|
| `doro.iam.jwks-uri` | `http://localhost:8080/.well-known/jwks.json` | IAM의 JWKS 주소. 컨테이너에서는 `http://auth-api:8080/.well-known/jwks.json` |
| `doro.iam.issuer` | `https://auth.doro.local` | 기대하는 `iss` 값(`issuer-validation`이 켜졌을 때만 사용) |
| `doro.iam.issuer-validation` | `OFF` | `OFF` / `WARN`(불일치 경고만) / `ENFORCE`(불일치면 인증 실패 = 익명) |
| `doro.iam.cookie-name` | `""` | `Authorization` 헤더가 없을 때 액세스 토큰을 읽을 쿠키 이름. 비면 쿠키를 읽지 않음 |
| `doro.iam.clock-skew-seconds` | `5` | JWT 시계 오차 허용(초) |
| `doro.iam.audience` | `""` | 비어 있으면 `aud` 있는 토큰을 **거부**(아래 참고). 값이 있으면 `aud`에 그 값이 있는 토큰만 허용 |
| `doro.iam.jwks-prefetch` | `true` | 기동 시 JWKS를 백그라운드로 미리 조회(실패해도 기동은 계속) |
| `doro.iam.revocation-check` | `OFF` | `OFF` / `WARN` / `ENFORCE` — 세션 폐기 확인([4.8](#48-세션-폐기-확인)) |
| `doro.iam.revocation-url` | `""` | 세션 확인 URL. 비면 `jwks-uri`의 `scheme://host[:port]` + `/api/v1/sessions/current` |
| `doro.iam.revocation-cache-seconds` | `30` | "유효" 판정을 세션(sid)별로 재사용하는 시간(초) |
| `doro.iam.revocation-timeout-millis` | `2000` | IAM 세션 확인 호출의 연결/읽기 타임아웃 |
| `doro.iam.revocation-fail-open` | `true` | IAM 판정을 못 받았을 때 통과시킬지 |
| `doro.iam.revocation-failure-backoff-seconds` | `10` | IAM이 판정을 못 낸 뒤 이 시간 동안은 IAM을 다시 부르지 않고 바로 fail-open/closed 정책 적용(0이면 매번 시도) |
| `doro.guard.grpc-host` | `localhost` | Guard gRPC 호스트(컨테이너에서는 `guard-api`) |
| `doro.guard.grpc-port` | `9090` | Guard gRPC 포트 |
| `doro.guard.enabled` | `true` | `false`면 `DoroGuardClient` 빈을 만들지 않음. 이때 `@DoroGuard`는 **모든 호출을 거부**(fail-closed) |
| `doro.guard.service-token` | `""` | Guard 서비스 토큰. 비면 헤더를 보내지 않음. Guard가 `WARN`/`ENFORCE`면 필요 |
| `doro.web.exception-handler` | `true` | 기본 예외 처리 `@RestControllerAdvice` 등록 여부([4.7](#47-기본-예외-처리)). `DoroProperties`에는 없고 조건부로만 읽습니다 |

코드에 고정된 값(속성 아님): Guard gRPC 호출 데드라인 **3초**(재시도 없음), JWKS 키 TTL 10분(만료 시 요청을 막지 않고 백그라운드 갱신), 모르는 `kid`는 30초 쿨다운으로 동기 재조회, JWKS HTTP 타임아웃 3초.

최소 설정 예:
```yaml
doro:
  iam:
    jwks-uri: http://${IAM_HOST:localhost}:${IAM_PORT:8080}/.well-known/jwks.json
  guard:
    grpc-host: ${GUARD_GRPC_HOST:localhost}
    grpc-port: ${GUARD_GRPC_PORT:9090}
    service-token: ${DORO_GUARD_SERVICE_TOKEN:}
```

### 4.3 필터가 하는 일과 하지 않는 일

`DoroJwtAuthFilter`는 웹 애플리케이션에서 모든 요청 앞(가장 높은 우선순위)에 등록됩니다.

**하는 일**
- `Authorization: Bearer <JWT>`(없으면 `doro.iam.cookie-name` 쿠키)에서 토큰을 읽어 **RS256 서명, 만료(`exp` 필수)**를 검증하고, 토큰 `kid`로 JWKS 공개키를 고릅니다.
- `iss`는 `issuer-validation`에 따라, `aud`는 아래 규칙대로 검증합니다.
- 검증된 사용자를 요청 스레드의 `ThreadLocal`(`DoroUserContext`)에 두었다가 응답 후 지웁니다. (비동기 스레드로는 전파되지 않습니다.)
- 요청마다 `X-Trace-Id`(안전한 값이면 그대로, 아니면 새 UUID)를 MDC와 응답 헤더에 실어, Guard 호출(gRPC 메타데이터)로도 전파합니다.
- `revocation-check`를 켜면 IAM에 세션 상태를 확인합니다([4.8](#48-세션-폐기-확인)).

**`aud` 규칙**: `doro.iam.audience`가 비어 있으면 **`aud`가 있는 토큰은 인증 실패**(OIDC id_token이 API 인증에 쓰이는 토큰 혼동 방지)이고, 값이 있으면 `aud`에 그 값이 없는 토큰이 인증 실패입니다. IAM이 `aud`를 넣는 것은 id_token뿐이라, **audience를 설정하면 일반 액세스 토큰(`aud` 없음)은 거부됩니다.** 일반적인 서비스는 비워 두세요.

**하지 않는 일 (꼭 읽어 주세요)**
1. **요청을 막지 않습니다.** 토큰이 없거나 틀려도(서명 불일치, 만료, `kid` 없음 등) **예외 없이 익명(`DoroUser.anonymous()`)으로 통과**합니다. 인증 강제는 `@DoroGuard`나 컨트롤러의 `isAuthenticated()` 확인으로 직접 하세요. 실패 이유는 WARN 로그에만 남습니다.
2. **기본으로는 세션 폐기를 확인하지 않습니다.** 로그아웃/세션 종료/비밀번호 변경 후에도 액세스 토큰은 **만료(기본 15분)까지 서브 서비스에서 유효**합니다. 즉시 반영하려면 `revocation-check`를 켜야 합니다.
3. **`role` 클레임은 낡을 수 있습니다.** 토큰 수명 동안 발급 시점의 값이 유지됩니다(IAM은 역할 변경 시 대상의 세션을 종료하지만, 그 반영은 위 2번과 같이 `revocation-check`가 켜져 있어야 서브 서비스에 도달합니다). **관리자 여부 같은 중요한 판정은 `role` 클레임이 아니라 Guard 튜플로** 하세요.
4. `iss`는 기본으로 검증하지 않습니다(`issuer-validation: OFF`).
5. OAuth 액세스 토큰(`cid`)과 일반 토큰을 구분하지 않습니다. 둘 다 사용자 토큰으로 받아들이며 OAuth 토큰의 `role`은 `USER`입니다.
6. Spring Security의 `SecurityContext`를 채우지 않습니다.

### 4.4 CurrentDoroUser

컨트롤러 파라미터에 현재 사용자를 주입합니다. 지원 타입은 `DoroUser`, `UUID`, `String` 세 가지입니다.

```java
@GetMapping("/me")
public Profile me(@CurrentDoroUser DoroUser user) { ... }          // 항상 non-null
@GetMapping("/mine")
public List<Doc> mine(@CurrentDoroUser UUID userId) { ... }        // 익명이면 null !
@GetMapping("/mine2")
public List<Doc> mine2(@CurrentDoroUser String userId) { ... }     // 익명이면 null !
```

| 타입 | 익명(비로그인)일 때 |
|---|---|
| `DoroUser` | `DoroUser.anonymous()` — `userId=null`, `email="anonymous"`, `role="ANON"` |
| `UUID` / `String` | **`null`** |

`DoroUser`는 `userId`(UUID), `email`, `sessionId`(UUID, 토큰의 `sid`), `userIndex`(`uidx`), `role` 필드와 `isAuthenticated()`, `isAdmin()`, `isSuperAdmin()`을 가집니다(`isAdmin`도 클레임 기반이라 [4.3](#43-필터가-하는-일과-하지-않는-일)의 3번을 따릅니다).

> **`UUID`/`String`으로 받으면 익명일 때 `null`입니다.** 로그인이 필수인 엔드포인트에서 이 값을 그대로 DB 조회에 쓰면 NPE나 "남의 데이터 조회"로 이어질 수 있으니, 필수 로그인에는 `DoroUser` + `isAuthenticated()` 확인이나 `@DoroGuard`를 쓰세요.

### 4.5 DoroGuard

메서드(또는 클래스)에 붙이면 호출 전에 Guard에 `Check`를 보내고, 거부되면 `DoroAccessDeniedException`을 던집니다.

```java
// 1) 단축형: "네임스페이스:객체표현식#릴레이션"
@DoroGuard("document:#docId#editor")
@PutMapping("/docs/{docId}")
public Doc update(@PathVariable String docId, @RequestBody UpdateReq req) { ... }

// 2) 속성형
@DoroGuard(namespace = "blog_post", object = "#postId", relation = "editor")
@DeleteMapping("/posts/{postId}")
public void delete(@PathVariable UUID postId) { ... }

// 3) 중첩 DTO 의 필드 (SpEL)
@DoroGuard(namespace = "document", object = "#req.docId", relation = "viewer")
@PostMapping("/docs/export")
public Export export(@RequestBody ExportReq req) { ... }

// 4) 고정 객체 (SpEL 이 아니라 리터럴): 관리자만
@DoroGuard("system:doro#admin")
@GetMapping("/internal/stats")
public Stats stats() { ... }

// 5) 대리 검사: 검사 대상 사용자를 직접 지정
@DoroGuard(namespace = "document", object = "#docId", relation = "viewer", subject = "#targetUserId")
@GetMapping("/docs/{docId}/can-view/{targetUserId}")
public boolean canView(@PathVariable String docId, @PathVariable String targetUserId) { ... }

// 6) 클래스 레벨: 모든 메서드에 적용(메서드에 @DoroGuard 가 있으면 그것이 우선)
@DoroGuard("workspace:#wsId#member")
@RestController
@RequestMapping("/workspaces/{wsId}")
class WorkspaceController { ... }
```

- **단축형 파싱**: 첫 `:` 앞이 네임스페이스, 마지막 `#` 뒤가 릴레이션, 그 사이가 객체 표현식입니다. `"document:#docId#editor"` → `document` / `#docId` / `editor`. 형식이 틀리면 `IllegalArgumentException`.
- **SpEL**: `#`로 **시작하는** 값만 SpEL로 평가하고 나머지는 리터럴입니다. 쓸 수 있는 변수는 **파라미터 이름**(`-parameters` 필요), `#p0`/`#a0`(위치), `#args`입니다. 결과는 `toString()`되어 객체 ID가 되므로 `UUID`/`Long` 파라미터도 됩니다.
- **누구를 검사하나**: `subject`가 비어 있으면 현재 로그인 사용자의 `userId`로 검사합니다(`subjectNamespace`는 `user` 고정). 이때 **로그인하지 않았으면** `DoroAccessDeniedException`(→ 기본 `401`)입니다. `subject`를 지정하면 로그인 여부와 무관하게 그 값으로 검사합니다.
- 객체 표현식이 `null`/빈 값이면 빈 문자열이 되어 Guard가 `INVALID_ARGUMENT`로 거절하고, `check`가 `false`를 반환해 결국 `403`입니다(파라미터 이름을 못 찾을 때 흔한 증상).
- **Guard 장애/토큰 오류도 `false`** (`check`는 예외를 삼키고 `false`)이므로 `@DoroGuard`에서는 **`403`으로 보입니다**(`503`이 아님). 로그의 `DoroGuardClient check failed`를 보세요.
- `doro.guard.enabled=false`면 `@DoroGuard` 대상 호출은 전부 거부됩니다.
- Spring AOP 프록시 기반이므로 같은 객체 안에서 `this.method()`로 부르는 경우는 가로채지 못합니다(Spring 일반 동작).

### 4.6 DoroGuardClient

`doro.guard.enabled=true`(기본)이면 빈으로 주입받아 직접 쓸 수 있습니다. 모든 메서드의 `subjectNamespace` 기본값은 `user`이고, `subjectRelation`은 `null`이면 빈 문자열(= 없음)로 보냅니다.

| 메서드 | 정상 | **실패(Guard 장애/토큰 오류/입력 오류)** |
|---|---|---|
| `check(ns, id, rel, subjectId)` (오버로드 있음) | `boolean` | **`false`** (예외 없음, fail-closed) |
| `checkOrThrow(...)` | `boolean` | `DoroGuardUnavailableException` |
| `writeTuple(ns, id, rel, subjectNs, subjectId[, subjectRel])` | 새로 쓴 개수(`int`) | **`0`** (예외 없음) |
| `writeTupleOrThrow(ns, id, rel, subjectNs, subjectId, subjectRel)` | 새로 쓴 개수 | `DoroGuardWriteFailedException` |
| `deleteTuple(...)` | 삭제한 개수 | **`0`** |
| `deleteTupleOrThrow(...)` | 삭제한 개수 | `DoroGuardWriteFailedException` |
| `expand(ns, id, rel)` | 트리 JSON 문자열 | **`null`** |
| `expandOrThrow(...)` | 트리 JSON 문자열 | `DoroGuardUnavailableException` |

> **`writeTuple`이 `0`을 반환하는 것은 "이미 있음"일 수도 "실패"일 수도 있습니다.** 서비스 DB와 Guard 상태를 맞춰야 하는 곳(리소스 생성/삭제)에서는 **`*OrThrow`**를 쓰고 실패를 직접 처리하세요([5단계](#5-새-서브-서비스-블루프린트)). 권한 체크도 "거부"와 "장애"를 구분해야 하면 `checkOrThrow`를 쓰세요.

```java
@Service
@RequiredArgsConstructor
class DocService {
    private final DoroGuardClient guard;

    boolean canEdit(UUID userId, UUID docId) {
        return guard.check("document", docId.toString(), "editor", userId.toString());
    }

    void share(UUID docId, String groupId) {                 // document:{id}#viewer@group:{g}#member
        guard.writeTupleOrThrow("document", docId.toString(), "viewer", "group", groupId, "member");
    }
}
```
`DoroGuardClient`는 `shutdown()`을 제공하지만 빈 소멸 시 자동 호출 설정은 확인하지 못했습니다 `(미검증)`.

### 4.7 기본 예외 처리

`doro.web.exception-handler`(기본 `true`)면 `DoroExceptionHandlerAdvice`가 `@RestControllerAdvice`(가장 낮은 우선순위)로 등록됩니다. **서비스가 같은 예외에 자체 `@ExceptionHandler`를 두면 그쪽이 우선**합니다. 사용자 ID/객체 ID는 응답에 싣지 않습니다.

| 예외 | HTTP | 본문 |
|---|---|---|
| `DoroAccessDeniedException`, **익명** 요청 | `401` | `{"success":false,"code":"UNAUTHORIZED","message":"로그인이 필요합니다.","status":401}` |
| `DoroAccessDeniedException`, 로그인 요청 | `403` | `{"success":false,"code":"ACCESS_DENIED","message":"해당 리소스에 대한 권한이 없습니다.","status":403}` |
| `DoroGuardUnavailableException` | `503` | `{"success":false,"code":"GUARD_UNAVAILABLE","message":"인가 서버를 일시적으로 사용할 수 없습니다.","status":503}` |

- `DoroGuardUnavailableException`은 `DoroAccessDeniedException`을 **상속**합니다. 서비스가 `DoroAccessDeniedException`용 핸들러만 만들면 장애도 그쪽(403)으로 가므로, 장애를 `503`으로 구분하려면 `DoroGuardUnavailableException` 핸들러를 따로 두세요(doro-blog가 이렇게 합니다). 이 예외는 `checkOrThrow`/`expandOrThrow`를 직접 쓸 때만 발생합니다.
- **`DoroGuardWriteFailedException`(`writeTupleOrThrow` 등의 실패)은 기본 핸들러가 처리하지 않습니다.** 서비스가 직접 매핑하지 않으면 일반 `500`이 됩니다.
- 로그인 필수인 엔드포인트에서 직접 던지려면 `throw new DoroAccessDeniedException("로그인이 필요합니다.")` 하면 익명일 때 `401`로 변환됩니다.

### 4.8 세션 폐기 확인

**문제**: SDK는 JWT를 로컬에서 검증하므로, IAM에서 로그아웃/세션 종료/비밀번호 변경/역할 변경을 해도 **이미 발급된 액세스 토큰은 만료될 때까지 서브 서비스에서 통과**합니다.

**해결(선택)**: `doro.iam.revocation-check`를 `WARN` 또는 `ENFORCE`로 켜면, JWT 검증이 끝난 뒤 같은 토큰으로 IAM의 `GET /api/v1/sessions/current`를 호출해 세션이 살아 있는지 확인합니다(IAM이 DB 기준으로 답하며 세션을 갱신하지 않습니다).

```yaml
doro:
  iam:
    revocation-check: ENFORCE       # OFF(기본) | WARN | ENFORCE
    # revocation-url: https://auth.example.com/api/v1/sessions/current   # jwks-uri 에 경로 prefix 가 있으면 명시
```

| IAM 응답 | 판정 | 캐시 |
|---|---|---|
| `204` | 유효(`ACTIVE`) | `revocation-cache-seconds`(30초) 동안 sid별로 재사용 |
| `401` / `403` | 폐기(`REVOKED`) | **토큰 만료 시각까지** 다시 묻지 않음 |
| 연결 실패, 타임아웃, 5xx, 그 밖의 응답 | 판정 불가(`UNAVAILABLE`) | 캐시 안 함. 이후 `revocation-failure-backoff-seconds`(10초) 동안은 IAM을 부르지 않고 바로 정책 적용 |

- 같은 sid에 대한 동시 조회는 IAM 호출 1회로 합쳐지고, 캐시는 최대 1만 항목(오래된 것부터 제거)입니다. 원본 토큰은 캐시 키로 쓰거나 로그에 남기지 않습니다.
- 모드별 동작: **`ENFORCE`** — 폐기면 요청을 **익명으로 처리**(요청을 거부하는 것이 아니라 `DoroUser.anonymous()`로 통과시킨 것이므로, 인증이 필요한 곳은 `@DoroGuard`/`isAuthenticated()`가 `401`을 만듭니다). **`WARN`** — 폐기여도 경고 로그만 남기고 통과.
- **fail-open**: 판정 불가일 때 `revocation-fail-open: true`(기본)면 통과, `false`이고 `ENFORCE`면 익명 처리합니다(`WARN`은 항상 통과).
- 토큰에 `sid`가 없으면 확인을 건너뜁니다(한 번만 경고 로그). `revocation-url`이 비었는데 `jwks-uri`도 해석할 수 없으면 확인을 끄고 경고를 남깁니다.
- OAuth 액세스 토큰(`cid`)으로도 이 확인이 동작합니다(IAM이 이 경로에서 `cid` 토큰을 인정합니다).

**언제 켜나**: 로그아웃/세션 종료/탈취 대응이 **바로 효력을 가져야 하는** 서비스(결제, 관리 기능 등). 대가는 서비스 요청마다 늘어나는 IAM 확인(캐시 30초 포함)과 IAM 가용성 의존입니다. "유효" 캐시 때문에 폐기가 **최대 30초** 늦게 반영될 수 있습니다. 읽기 위주의 공개 콘텐츠 서비스는 기본값 `OFF`로 충분할 수 있습니다.

---

## 5. 새 서브 서비스 블루프린트

doro-blog를 참조 구현으로 하는 5단계입니다(`blog`는 서비스 이름 예시).

### 1단계. 독립 DB 만들기

- 서비스마다 DB를 따로 씁니다: **`service_{name}`**(예: `service_blog`). Flyway 히스토리 테이블도 `{name}_schema_history`로 분리하고 `spring.jpa.hibernate.ddl-auto: validate`를 유지합니다. 스키마 변경은 항상 Flyway 마이그레이션 스크립트로 합니다.
- 저장소의 `docker-compose.yml`은 `POSTGRES_MULTIPLE_DATABASES`(기본 `doro_auth,doro_guard`)에 나열된 DB를 `init-db/init-databases.sh`가 **Postgres 볼륨이 처음 만들어질 때** 생성합니다. 새 서비스는 이 목록과 `.env.example`/`.env`에 `service_{name}`을 추가하세요. **이미 볼륨이 있는 서버에서는 init 스크립트가 다시 돌지 않으므로** DB를 직접 만들어야 합니다(`CREATE DATABASE service_{name};` — Postgres 공식 이미지 동작, 이 저장소의 CI도 auth/guard DB를 같은 방식으로 보강합니다).

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:service_blog}
    username: ${DB_USER:doro_admin}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    table: blog_schema_history
    locations: classpath:db/migration
```

### 2단계. SDK 의존성과 설정

[4.1](#41-의존성)의 composite build와 `-parameters`를 적용하고, 설정을 연결합니다. 컨테이너 안에서는 Docker 네트워크의 서비스 이름을 씁니다.

```yaml
doro:
  iam:
    jwks-uri: http://${IAM_HOST:localhost}:${IAM_PORT:8080}/.well-known/jwks.json     # 컨테이너: auth-api:8080
  guard:
    grpc-host: ${GUARD_GRPC_HOST:localhost}                                           # 컨테이너: guard-api
    grpc-port: ${GUARD_GRPC_PORT:9090}
    service-token: ${DORO_GUARD_SERVICE_TOKEN:}                                       # Guard 가 WARN/ENFORCE 면 필수
    # 아래 둘은 SDK 속성이 아니라 이 서비스의 스키마 등록 코드가 읽는 사용자 정의 속성입니다 (doro-blog)
    http-url: http://${GUARD_HTTP_HOST:localhost}:${GUARD_HTTP_PORT:8081}
    http-timeout-ms: ${GUARD_HTTP_TIMEOUT_MS:5000}
```
- Guard가 `ENFORCE`면 이 서비스용 호출자 토큰을 Guard의 `DORO_GUARD_SERVICE_TOKENS`에 등록하고(`blog:<토큰>:schema-write`), 서비스에는 같은 값을 `DORO_GUARD_SERVICE_TOKEN`으로 줍니다. **스키마를 등록하는 서비스에만 `schema-write`**를 주세요.

### 3단계. 권한 스키마 정의와 병합 등록

1. 서비스 접두사를 붙인 네임스페이스로 `.doro`를 씁니다(타입 이름 충돌 방지). doro-blog의 실제 파일(`src/main/resources/blog-schema.doro`):
   ```text
   type blog_series {
     relation owner: user
     relation editor: owner
     relation viewer: user | editor
   }

   type blog_post {
     relation author: user
     relation series: blog_series
     relation editor: author
     relation viewer: author | user
   }

   type blog_comment {
     relation author: user
     relation post: blog_post
     relation editor: author
     relation can_delete: author | post#author
   }
   ```
   (릴레이션 선언 순서를 지켰습니다: `editor`가 `author` 뒤에, `viewer`가 `editor` 뒤에 옵니다. `post#author`는 "이 댓글의 `post` 튜플이 가리키는 글의 `author`"인 TTU입니다.)
2. 등록은 **GET → 병합 → POST**입니다([3.5](#35-스키마-조회와-등록)). 서비스 기동 시 자동으로 하는 참조 구현(`BlogSchemaInitializer`, 요약):
   ```java
   @Component
   @RequiredArgsConstructor
   public class BlogSchemaInitializer implements ApplicationRunner {
       private final ResourceLoader resourceLoader;
       private final ObjectMapper objectMapper = new ObjectMapper();
       @Value("${doro.guard.http-url}")            private String guardHttpUrl;
       @Value("${doro.guard.http-timeout-ms}")     private int timeoutMs;
       @Value("${doro.guard.service-token:}")      private String serviceToken;

       @Override
       public void run(ApplicationArguments args) {
           RestTemplate rest = newRestTemplate(timeoutMs);              // 연결/읽기 타임아웃 필수: Guard 가 죽어도 기동이 멈추지 않게
           try {
               String url = guardHttpUrl + "/api/v1/guard/schema";
               ResponseEntity<String> res = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
               String activeDsl = objectMapper.readTree(res.getBody()).path("data").asText("");   // data 는 DSL 문자열

               if (activeDsl.contains("type blog_post") && activeDsl.contains("type blog_series")) return;   // 멱등

               String blogDsl = new String(resourceLoader.getResource("classpath:blog-schema.doro")
                       .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
               HttpHeaders h = headers(); h.setContentType(MediaType.APPLICATION_JSON);
               rest.postForEntity(url, new HttpEntity<>(Map.of("dsl", activeDsl + "\n\n" + blogDsl), h), String.class);
           } catch (Exception e) {
               log.warn("Guard schema dynamic sync skipped: {}", e.getMessage());   // Guard 가 꺼져 있어도 서비스는 뜬다
           }
       }
       private HttpHeaders headers() {
           HttpHeaders h = new HttpHeaders();
           if (!serviceToken.isBlank()) h.set("X-Doro-Service-Token", serviceToken);
           return h;
       }
   }
   ```
   - `doro.guard.http-url`/`http-timeout-ms`/`service-token` 중 앞의 둘은 **서비스가 정한** 속성입니다(SDK는 REST를 쓰지 않습니다).
   - 이 구현은 "내 타입이 이미 있으면 건너뛴다"만 합니다. 이미 등록된 타입을 **수정**하려면 이 방식으로는 반영되지 않으므로, 병합 로직을 직접 확장하거나 [3.5](#35-스키마-조회와-등록)의 절차로 수동 등록하세요.
3. 등록 직후 `check`로 한두 개를 확인해 보세요(특히 Guard가 기본 `WARN` 모드면 오타가 조용히 통과합니다).

### 4단계. 컨트롤러 보호와 튜플 동기화

**조회는 공개로 두고, 변경은 `@DoroGuard` 또는 로그인 확인으로 보호**하는 것이 일반적입니다.

```java
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
class PostController {
    private final PostCommandService commands;

    @PostMapping                                              // 로그인만 되어 있으면 작성 가능
    public ApiResponse<PostResponse> create(@CurrentDoroUser DoroUser user, @Valid @RequestBody CreatePostRequest req) {
        if (!user.isAuthenticated()) throw new DoroAccessDeniedException("로그인이 필요합니다.");   // 익명이면 기본 핸들러가 401
        return ApiResponse.success(commands.create(user, req));
    }

    @DoroGuard(namespace = "blog_post", object = "#postId", relation = "editor")   // Guard 판정: 글의 editor 만
    @PutMapping("/{postId}")
    public ApiResponse<PostResponse> update(@PathVariable("postId") UUID postId, @Valid @RequestBody UpdatePostRequest req) {
        return ApiResponse.success(commands.update(postId, req));
    }
}
```

**튜플 동기화**: 리소스를 만들 때 소유 튜플을 쓰고, 지울 때 튜플도 지웁니다. 서비스 DB와 Guard는 **하나의 트랜잭션이 아니므로** 방향별로 안전한 쪽을 택해야 합니다. doro-blog의 `GuardTuples` 래퍼(요약):

```java
@Component
@RequiredArgsConstructor
public class GuardTuples {
    private final DoroGuardClient guardClient;

    /** 쓰기: 트랜잭션 안에서 즉시 쓰고, 실패하면 예외 -> DB 도 롤백. DB 가 나중에 롤백되면 이미 쓴 튜플을 정리한다. */
    public void write(String ns, String id, String rel, String subjectNs, String subjectId) {
        guardClient.writeTupleOrThrow(ns, id, rel, subjectNs, subjectId, null);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try { guardClient.deleteTuple(ns, id, rel, subjectNs, subjectId); }       // 보상(compensation)
                        catch (RuntimeException e) { log.error("orphan Guard tuple needs manual cleanup: {}:{}#{}", ns, id, rel, e); }
                    }
                }
            });
        }
    }

    /** 삭제: 커밋이 확정된 뒤에 지운다. 먼저 지웠는데 DB 삭제가 실패하면 글은 남고 권한만 사라지기 때문. */
    public void deleteAfterCommit(String ns, String id, String rel, String subjectNs, String subjectId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { guardClient.deleteTuple(ns, id, rel, subjectNs, subjectId); }
                catch (RuntimeException e) { log.error("stale permission remains: {}:{}#{}", ns, id, rel, e); }
            }
        });
    }
}
```
서비스에서는 `guardTuples.write("blog_post", saved.getId().toString(), "author", "user", userId.toString())`처럼 호출합니다. 그리고 Guard 쓰기 실패(`DoroGuardWriteFailedException`)와 장애(`DoroGuardUnavailableException`)를 서비스의 전역 예외 처리기에서 `503`으로 매핑하세요([4.7](#47-기본-예외-처리)).

> **주의**: 튜플로 쓰는 타입/릴레이션은 위 3단계 스키마에 **선언돼 있어야** 합니다. 선언돼 있지 않으면 `WARN` 모드에서는 로그만 남기고 써지지만, Guard가 `ENFORCE`면 `INVALID_TUPLE`로 거부됩니다. (참고: doro-blog의 `FollowService`는 `blog_user#follower` 튜플을 쓰는데 `blog-schema.doro`에는 `blog_user` 타입이 없습니다. 지금은 `WARN` + 실패를 삼키는 `writeTuple`이라 문제가 드러나지 않습니다.)

### 5단계. 게이트웨이, compose, CI

- **compose**: 서비스 컨테이너를 `doro-network`에 연결하고, 로그는 **stdout**(SLF4J)으로만 내보내 서버별 수집기(Grafana Alloy)가 중앙 Loki로 수집하게 합니다. `X-Trace-Id`는 SDK가 HTTP/gRPC로 전파합니다. 서비스 컨테이너에서 IAM/Guard는 `auth-api:8080`, `guard-api:9090`(gRPC)/`guard-api:8081`(REST) 이름으로 접근합니다.
- **게이트웨이**: 같은 origin으로 노출하려면 `gateway/nginx.conf`에 upstream과 `location`을 추가합니다. 현재 설정에서 `/api/v1/` 전체는 블로그 백엔드로 가는 catch-all이라, 새 서비스는 **더 구체적인 prefix**(예: `/api/v1/shop/`)를 그보다 위에 둬야 합니다. 게이트웨이는 **compose 프로젝트 밖의 컨테이너라 CI가 배포하지 않으므로**, 서버의 nginx.conf를 직접 갱신하고 `nginx -t` 후 reload 해야 합니다.
- **CI/배포**: `main`에 push하면 self-hosted runner가 테스트(`scripts/ci-test.sh`) → 배포(`docker compose up -d --build`) → IAM/Guard `/actuator/health` 확인을 합니다. 서브 서비스(doro-blog)는 이 파이프라인에 포함되지 않고 수동으로 배포합니다. 배포 뒤에는 서비스의 `/actuator/health`가 `UP`인지 확인하세요.
- **CORS**: 브라우저가 서비스를 다른 origin에서 직접 호출한다면 서비스 쪽 CORS 설정이 필요합니다(IAM의 `DORO_CORS_ALLOWED_ORIGIN_PATTERNS`는 IAM에만 적용됩니다).

---

## 6. 문제 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| IAM API가 **본문 없는 `401`** | `Authorization` 헤더 없음, 만료/위조 토큰, 폐기된 세션의 토큰, OAuth 액세스 토큰/`id_token`으로 IAM 일반 API 호출 | 로그인 토큰을 `Bearer`로 보내고, 만료면 `POST /auth/token/refresh`. OAuth 토큰은 `userinfo`/`sessions/current`에서만 통함 |
| `401 INVALID_CREDENTIALS` | 이메일/비밀번호 불일치(구분 안 함). 비밀번호 변경 API의 `currentPassword` 불일치도 같은 코드 | 입력 확인. 5회 연속 틀리면 잠김 |
| `403 ACCOUNT_LOCKED` | 5회 연속 실패(비밀번호/OTP 합산) | 15분 대기. 관리자가 2FA 초기화(`DELETE /admin/users/{id}/2fa`)해도 카운터가 지워짐. (**423이 아닙니다**) |
| `429 TOO_MANY_REQUESTS` + `Retry-After` | IP 단위 요청 제한(로그인 20/10분, lookup 30/10분, 가입 10/시간, OAuth 토큰·인가 60/10분) 또는 2FA 티켓/인가 코드 저장 한도 | `Retry-After` 초 뒤 재시도. 프록시 뒤라면 `DORO_IAM_TRUSTED_PROXIES`에 프록시를 넣어야 사용자별 IP로 구분됩니다 |
| 다시 로그인했더니 **이전 토큰이 `401`** | 같은 IP + 같은 `User-Agent`의 이전 활성 세션은 새 로그인 때 폐기됨. 또는 세션 상한(10) 초과로 가장 오래된 세션이 종료됨 | 기기별로 다른 `User-Agent`/세션을 쓰거나, 사용 중인 세션의 토큰만 쓰세요 |
| 2FA 코드가 맞는데 `INVALID_2FA_CODE` | 방금 `verify`/`login`에 쓴 코드(같은 30초 스텝)는 재사용 거부. 시계 오차(허용 ±1스텝=±30초 초과) | 다음 코드가 나올 때까지 기다리기. 기기 시간 동기화 |
| 2FA 로그인에서 `400 INVALID_TOKEN` | `tempTicket` 만료(5분), 5회 실패로 폐기, IAM 재시작으로 소실 | 처음부터 다시 `login` |
| `POST /auth/login` 응답에 토큰이 없음 | 2FA 계정: `data.requires2fa=true` + `tempTicket`. 응답은 `status:"SUCCESS"` 형태가 아님 | `/2fa/login`으로 이어서 진행. 토큰 위치는 `/login`이 `data.tokens.*`, `/2fa/login`이 `data.*` |
| `400 TOKEN_REUSE_DETECTED` | 이미 쓴(회전된) 리프레시 토큰을 다시 보냄 → 탈취로 간주해 세션 종료됨 | 다시 로그인. 클라이언트는 갱신 응답의 **새 refreshToken으로 즉시 교체**하고 동시 갱신(여러 탭)을 직렬화 |
| 로그아웃했는데 **서브 서비스에서는 토큰이 계속 통함** | SDK는 기본으로 세션 폐기를 확인하지 않음 → 토큰 만료(기본 15분)까지 유효 | SDK에서 `doro.iam.revocation-check: ENFORCE` ([4.8](#48-세션-폐기-확인)). 최대 30초(캐시) 지연 가능 |
| 서비스에서 `@CurrentDoroUser UUID`가 `null`로 NPE | 익명 요청이면 `UUID`/`String`은 `null` | `DoroUser`로 받아 `isAuthenticated()`를 확인하거나 `@DoroGuard` 사용 |
| `@DoroGuard` 엔드포인트가 **익명에게 `401`**, 로그인했는데도 `401` | 필터가 토큰을 익명으로 처리함: 서명/만료 실패, `kid`를 JWKS에서 못 찾음, `issuer-validation: ENFORCE`인데 `iss` 불일치, `aud`가 있는 토큰(id_token) 사용, `revocation-check: ENFORCE`에서 폐기 판정 | 서비스 로그의 `Failed to authenticate Doro JWT`, `JWT issuer mismatch` 등을 확인. `doro.iam.jwks-uri`가 IAM을 가리키는지 확인 |
| `@DoroGuard`가 계속 **`403`** (권한이 있어야 하는데) | (1) 튜플이 없거나 스키마 규칙이 의도와 다름 (2) **DSL 전방 참조**: 릴레이션을 선언 전에 써서 항상 false (3) SpEL이 비어 `objectId`가 `""` → Guard가 거절 (`-parameters` 누락, 오타) (4) Guard 장애/토큰 오류도 `check`가 `false` → 403 | Guard에 직접 `check`를 호출해 `allowed`/`reason` 확인(3.4). 서비스 로그 `DoroGuardClient check failed ...` 확인. 컴파일 옵션 `-parameters` 확인 |
| Guard가 `ENFORCE`인데 서비스가 모두 `403` | 서비스 토큰 없음/틀림 → gRPC `UNAUTHENTICATED` → SDK `check`가 `false` | `doro.guard.service-token`에 Guard의 호출자 토큰 설정. 서비스 로그 `UNAUTHENTICATED` 확인. 점검 중이면 Guard를 `WARN`으로 되돌리기 |
| 서비스의 스키마 등록(POST)이 `403 FORBIDDEN` | Guard가 `ENFORCE`인데 호출자 토큰에 `schema-write` 권한이 없음 | `DORO_GUARD_SERVICE_TOKENS`에 `이름:토큰:schema-write` 지정 |
| Guard REST가 `401 UNAUTHORIZED`(`success:false`, `code:UNAUTHORIZED`) | `ENFORCE`에서 `X-Doro-Service-Token` 누락/불일치 | 헤더 추가 |
| **스키마를 POST했더니 기존 타입(`system`, `user`, 다른 서비스)이 사라짐** → 관리자 API가 모두 `403`, 다른 서비스 권한 판정이 `false` | 스키마 등록은 **전체 교체**. 자기 타입만 보냄 | 백업해 둔 이전 DSL(또는 이전 `GET` 결과)에 내 타입을 합쳐 다시 POST. 항상 GET → 병합 → POST. (롤백 API는 없고 이전 버전은 DB `schema_definitions`에 남아 있음) |
| 스키마 등록은 성공했는데 **권한이 항상 `false`** | `WARN`(기본) 모드에서 전방 참조/오타가 경고만 남기고 등록됨. 또는 `group#member`를 멤버십 부여 수단으로 오해(TTU로 읽힘) | Guard 로그의 `Schema registered with N validation warning(s)` 확인. `DORO_GUARD_VALIDATION_MODE=ENFORCE`로 올려 줄 번호와 함께 거부되게 하기. 멤버십은 userset 튜플로 부여 |
| `400 INVALID_SYNTAX` | DSL 문법 오류(타입 블록 밖의 `relation`, `:` 누락, 타입 이름 누락), 또는 `ENFORCE`의 `line N: ...` | 메시지의 줄 번호 확인 |
| `400 INVALID_TUPLE` | `ENFORCE`에서 스키마에 없는 타입/릴레이션/subject 릴레이션 | 스키마에 선언하거나 튜플 수정(배치 전체가 거부됨) |
| 튜플은 썼는데 `check`가 옛 결과 | 인가 캐시(기본 60초) — 단일 인스턴스에서는 커밋 후 즉시 무효화되지만, 다중 인스턴스에서는 TTL만큼 지연 | 보통 즉시 반영됨. 다중 인스턴스라면 TTL 대기 |
| `POST /guard/schema`가 `415` | `text/plain` 등으로 보냄 | `Content-Type: application/json`, 본문 `{"dsl":"..."}` |
| `POST /guard/tuples`가 `400` | 본문이 배열이 아님(객체로 감쌈), 필드 누락, 길이 초과(64/128) | 위 형식의 JSON 배열로 |
| OAuth 인가가 `400` (`허용되지 않은 redirect_uri`) | 등록된 URI와 문자열이 1자라도 다름(끝 슬래시, 대소문자, 포트, 스킴), 또는 미등록 클라이언트인데 환경변수 허용 목록에 없음 | 등록 URI와 **완전히 같게**. 미등록이면 클라이언트를 등록 |
| OAuth 토큰 교환이 `invalid_grant` | 코드 재사용/만료(5분), 검증 실패 시에도 코드는 소비됨, `redirect_uri`/`client_id` 불일치, `code_verifier`가 챌린지와 불일치 | 인가 요청부터 다시. 챌린지는 `BASE64URL(SHA256(verifier))`(패딩 없음) |
| OAuth 응답에 `id_token`이 없음 | 인가 요청에 `scope=openid`를 넣지 않음(스코프를 생략하면 스코프 없음) | `scope=openid profile email` |
| OAuth 토큰 요청이 `invalid_request` | 토큰 파라미터를 쿼리스트링으로 보냄, 같은 파라미터 중복, `grant_type` 누락 | 본문(form)으로만 보내기 |
| OAuth 액세스 토큰으로 `GET /users/me`가 `401` | **의도된 동작**(토큰 격리). OAuth 토큰은 IAM의 `userinfo`/`sessions/current`에서만 인정 | 사용자 정보는 `GET /oauth2/userinfo` |
| 브라우저에서 토큰 엔드포인트 호출이 CORS 오류 | 호출하는 origin이 `DORO_CORS_ALLOWED_ORIGIN_PATTERNS`에 없음 | origin 추가(쉼표 구분, `*` 단독 불가). 서버 측 교환을 권장 |
| 게이트웨이 뒤에서 `/api/v1/admin/oauth/clients`가 404/블로그 백엔드 응답 | 서버의 nginx.conf에 `/api/v1/admin/oauth/` 라우트가 아직 반영되지 않음(없으면 `/api/v1/` catch-all 로 블로그 백엔드에 감). 게이트웨이는 compose 밖이라 CI가 배포하지 않음 | `gateway/nginx.conf`를 서버에 복사하고 `nginx -t` 후 reload. 그전에는 `/iam/api/v1/admin/oauth/clients` 또는 IAM에 직접 호출 |
| `GET /admin/users` 등 관리자 API가 `403` | 역할이 `ADMIN` 미만(본문 없는 403), 또는 Guard가 `system:doro#admin`을 인정하지 않음(`ACCESS_DENIED` 본문) — DB의 `role`만 바꾸고 IAM을 재시작하지 않았거나(튜플 미동기화), 역할 변경 후 다시 로그인하지 않아 토큰의 `role`이 옛 값 | IAM 재시작(기동 시 동기화) → 재로그인. Guard에서 `check`로 `system:doro#admin@user:{id}` 확인 |
| 역할 변경 API가 `403 ACCESS_DENIED` | `manage_roles`는 `super_admin`만 | `SUPER_ADMIN`으로 호출 |
| 새 서비스의 DB가 없어 기동 실패 | `POSTGRES_MULTIPLE_DATABASES`는 **빈 볼륨 최초 기동 때만** 처리됨 | 서버에서 `CREATE DATABASE service_{name};` |

---

## 7. 부록

### 7.1 IAM 엔드포인트 요약

| 메서드·경로 | 인증 | 설명 |
|---|---|---|
| `POST /api/v1/auth/signup` | 공개 | 가입 (201) |
| `POST /api/v1/auth/lookup` | 공개 | 계정 존재/프로필 조회 |
| `POST /api/v1/auth/login` | 공개 | 로그인 (`requires2fa`/`tempTicket`/`tokens`) |
| `POST /api/v1/auth/2fa/login` | 공개(티켓) | 2FA 로그인 (`data`=토큰) |
| `POST /api/v1/auth/token/refresh` | 공개(리프레시 토큰) | 토큰 회전 |
| `POST /api/v1/auth/2fa/setup`, `/2fa/verify`, `/2fa/disable` | Bearer | 2FA 등록/확정/해제 |
| `POST /api/v1/auth/logout[?sessionId=]` | Bearer | 로그아웃 |
| `GET /api/v1/users/me`, `PATCH /api/v1/users/me`, `PUT /api/v1/users/me/password` | Bearer | 내 정보 |
| `GET /api/v1/sessions`, `GET /api/v1/sessions/current`, `DELETE /api/v1/sessions/{id}`, `POST /api/v1/sessions/revoke-others?currentSessionId=` | Bearer | 세션 |
| `GET /api/v1/admin/users`, `PATCH /api/v1/admin/users/{id}/role`, `DELETE /api/v1/admin/users/{id}/2fa`, `GET /api/v1/admin/authz` | Bearer(ADMIN+) + Guard | 관리자 |
| `POST/GET /api/v1/admin/oauth/clients`, `DELETE /api/v1/admin/oauth/clients/{clientId}` | Bearer(ADMIN+) + Guard | OAuth 클라이언트 |
| `GET /oauth2/authorize`, `POST /oauth2/token`, `GET /oauth2/userinfo` | 본문 참조 | OAuth/OIDC |
| `GET /.well-known/jwks.json`, `GET /.well-known/openid-configuration` | 공개 | 공개키, discovery |
| `GET /health`, `GET /actuator/health` | 공개 | 상태 |

### 7.2 Guard 엔드포인트 요약

| 메서드·경로 | 본문 | `data` |
|---|---|---|
| `POST /api/v1/guard/check` | `{namespace, objectId, relation, subjectNamespace, subjectId, subjectRelation?}` | `{allowed, depth, reason}` |
| `POST /api/v1/guard/tuples` | 튜플 배열 | `{writtenCount}` |
| `DELETE /api/v1/guard/tuples` | 튜플 배열 | `{deletedCount}` |
| `GET /api/v1/guard/schema` | — | DSL 문자열 |
| `POST /api/v1/guard/schema` | `{"dsl": "..."}` | `{version, dsl, active}` |
| gRPC `doro.guard.v1.GuardService/{Check,WriteTuples,DeleteTuples,Expand}` | proto | — |

### 7.3 주요 환경변수

| 환경변수 | 기본 | 대상 | 설명 |
|---|---|---|---|
| `DORO_IAM_ISSUER` | `https://auth.doro.local` | IAM | JWT `iss`, discovery URL의 기준 |
| `DORO_IAM_ACCESS_TOKEN_TTL_SECONDS` | `900` | IAM | 액세스 토큰 수명 |
| `DORO_IAM_SESSION_MAX_ACTIVE_PER_USER` | `10` | IAM | 사용자당 활성 세션 상한(0 이하 무제한) |
| `DORO_IAM_RATE_LIMIT_ENABLED` / `_LOGIN_MAX` / `_TOKEN_MAX` | `true` / `20` / `60` | IAM | 요청 제한 |
| `DORO_IAM_TRUSTED_PROXIES` | `127.0.0.0/8,::1/128,172.16.0.0/12` | IAM | `X-Real-IP`를 믿을 프록시 |
| `DORO_IAM_TWO_FACTOR_MAX_PENDING_TICKETS` | `10000` | IAM | 2FA 임시 티켓 상한 |
| `DORO_IAM_PROFILE_IMAGE_MAX_LENGTH` | `1048576` | IAM | 프로필 이미지 최대 길이 |
| `DORO_CORS_ALLOWED_ORIGIN_PATTERNS` | 로컬 + 운영 도메인 | IAM | CORS 허용 origin(쉼표 구분) |
| `DORO_GUARD_URL` | `http://guard-api:8081`(compose) | IAM | IAM이 Guard REST를 부르는 주소 |
| `DORO_GUARD_SERVICE_TOKEN` | 비어 있음 | IAM·서비스·Guard | IAM/서비스: 보내는 토큰, Guard: 공유 토큰 |
| `DORO_IAM_JWT_KEY_ENCRYPTION_SECRET`, `DORO_IAM_JWT_PREVIOUS_KEY_ID`, `DORO_IAM_JWT_PREVIOUS_PUBLIC_KEY_PEM` | 비어 있음 | IAM | Redis의 JWT 개인키 암호화, 키 회전 |
| `DORO_OAUTH_*` | [2.8](#28-설정과-운영) | IAM | OAuth/OIDC |
| `DORO_GUARD_SECURITY_MODE` | `OFF` | Guard | 서비스 토큰 검사 `OFF`/`WARN`/`ENFORCE` |
| `DORO_GUARD_SERVICE_TOKENS` | 비어 있음 | Guard | `이름:토큰[:권한]` 쉼표 목록 |
| `DORO_GUARD_VALIDATION_MODE` | `WARN` | Guard | 튜플/스키마 검증 `OFF`/`WARN`/`ENFORCE` |
| `DORO_GUARD_SCHEMA_REFRESH_SECONDS` | `30` | Guard | DB 활성 스키마 확인 주기(0이면 끔) |
| `DORO_GUARD_CACHE_TTL_SECONDS` / `_CACHE_MAX_SIZE` | `60` / `50000` | Guard | 인가 캐시 |
| `DORO_GUARD_EXPAND_MAX_NODES` | `5000` | Guard | Expand 노드 상한 |
