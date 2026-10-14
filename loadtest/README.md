# Doro 인증/인가 부하 테스트 (k6)

auth(인증)와 Guard(인가)를 k6 로 부하 테스트하는 스크립트 모음이다.

## 준비
```bash
brew install k6
# 저장소 루트에서. rate limit 을 끄고 부트스트랩 토큰을 주는 로컬 전용 오버라이드를 함께 쓴다.
docker compose -f docker-compose.yml -f loadtest/compose.loadtest.yml up -d --build postgres redis guard-api auth-api
```
> 운영 서버에는 쓰지 말 것. 테스트가 사용자·튜플을 대량으로 만든다(로컬 DB 에 남는다).
> 이 오버라이드는 로컬 전용 테스트 토큰을 넣고 rate limit 을 끈다.

## 실행
| 파일 | 내용 |
|---|---|
| `auth-suite.js` | `SCENARIO`: admin-list-paged(관리자 목록 페이지 방식), lookup, public, login-fail, userinfo, sessions, session-churn, revoke-others, 2fa-enroll, 2fa-login, admin-read, admin-write, oauth-flow, oauth-refresh |
| `guard-suite.js` | `SCENARIO`: check-hit, check-miss, check-deep, check-cycle, write-race, write-check, schema, grpc-check, grpc-expand, mixed |
| `hash-saturation.js` | 틀린 비밀번호 로그인 폭주 중 `/users/me`·정상 로그인이 받는 영향. `-e ATTACK_RATE=150`(초당 고정 속도) 권장 |
| `lib.js` | 공용 도우미(사용자 준비, TOTP, PKCE) |
| `run-auth-matrix.sh`, `run-guard-matrix.sh` | 전체 매트릭스를 돌려 `results/*.md` 로 정리 |

```bash
k6 run -e SCENARIO=login-fail -e VUS=50 -e DURATION=15s loadtest/auth-suite.js
./loadtest/run-auth-matrix.sh      # 약 15분
./loadtest/run-guard-matrix.sh     # 약 10분
```
포트는 `.env` 기준(auth 28080, guard 28081, gRPC 9090)이다. 다르면 `-e AUTH=... -e GUARD=... -e GRPC=...`.

## 정확성 검사(오답 카운터)
성능만이 아니라 인가 결과가 맞는지도 센다. `wrong_answer` 가 0 이어야 한다.
- 로그아웃한 토큰이 거부되는가, 일반 사용자가 관리자 API 를 못 쓰는가
- Guard: 허용/거부 판정, 깊은 계층, 순환 그룹, 쓰기·삭제 직후 판정(낡은 캐시 여부)

## 주의
- 부하 발생기와 서버가 같은 기계에서 돌면 서로 CPU 를 나눠 쓴다. 수치는 **절대값이 아니라 전후 비교용**이다.
- 팬이 없는 기계(MacBook Air 등)는 스로틀링으로 결과가 흔들린다. 동시성과 시간을 낮춰서 돌린다.
- 2FA 는 같은 사용자의 같은 30초 구간 코드를 서버가 재사용 거부하므로, 시나리오가 사용자를 나눠 쓴다.
