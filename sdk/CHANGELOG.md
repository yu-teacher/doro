# Doro SDK 변경 내역

서비스는 CI·배포에서 Doro 를 태그(`sdk-vX.Y.Z`)로 받는다. 태그는 서비스의 `.doro-sdk-ref` 에 적는다.
올릴 때는 아래 "서비스가 할 일"을 먼저 하고 그 파일을 바꾸는 PR 을 연다.

## 0.2.0 (`sdk-v0.2.0`)

- **삭제**: 서비스 안에서 돌던 브라우저 로그인(BFF) 모듈(`com.hunnit_beasts.doro.sdk.bff`)과 그 자동 구성(`DoroBffAutoConfiguration`).
  로그인 로직은 독립 서비스 doro-bff(`bff` 모듈, `com.hunnit_beasts.doro.bff.core`)로 옮겼다. 서비스 안의 세션 쿠키 필터(`BffSessionAuthFilter`)는 없어졌다.
- 서비스가 할 일: `com.hunnit_beasts.doro.sdk.bff` 를 쓰는 코드(설정 빈, 세션 저장소, 사용자 훅, `BffCrypto`)를 먼저 지운다.
  게이트웨이가 붙여 주는 Bearer 검증과 `GET /api/v1/bff/session` 만 남긴다([BFF.md](https://github.com/yu-teacher/doro-gateway/blob/main/docs/BFF.md)).
- 그 밖의 SDK(토큰 검증, `@DoroGuard`, `@CurrentDoroUser`, Guard 클라이언트, 탈퇴 동기화)는 바뀌지 않았다.

## 0.1.0 (`sdk-v0.1.0`)

- 서비스가 태그로 고정해 쓰는 첫 기준점. 내장 BFF 모듈이 들어 있는 마지막 버전이다.
