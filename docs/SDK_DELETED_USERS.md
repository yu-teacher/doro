# SDK 탈퇴 동기화

계정 삭제 정책(30일 유예 후 익명화)을 모든 서비스에 적용하는 SDK 모듈이다. 서비스는 설정, 진행 위치 저장소, "그 사용자의 데이터를 어떻게 정리할지"만 제공하고, 가져오기·재시도·멱등 처리는 SDK 한 곳에 있다.

> 브라우저 로그인(BFF)은 SDK 에 없다. 서비스 밖의 독립 서비스 `doro-bff` 가 맡는다([BFF.md](BFF.md)). 서비스는 게이트웨이가 붙여 주는 Bearer 만 검증한다.

## 구성 (`com.hunnit_beasts.doro.sdk.sync`)

Doro 가 알려 주는 "영구 탈퇴한 사용자 ID"(`GET /internal/v1/deleted-users`, 서비스 토큰 인증)를 주기적으로 가져와 서비스가 자기 데이터를 정리한다.
계정 삭제 정책(30일 유예 후 익명화)을 모든 서비스에 적용하는 장치다. IAM 이 잠시 죽어 있어도 다음 주기에 이어서 처리하고, 같은 ID 를 다시 받아도 결과가 같다(멱등).

서비스가 할 일:
1. `DeletedUsersProperties` 빈(접두사 예 `blog.deleted-sync`): `enabled`, `page-size`(200), `overlap`(10분: 커밋 순서가 뒤바뀌어도 놓치지 않도록 기준 시각을 앞당겨 저장), `interval`(1시간), `initial-delay`, 타임아웃.
2. `DeletedUserHandler` 빈: 그 사용자의 데이터를 정리한다(익명화, 사진 삭제, Guard 튜플 정리). 예외를 던지면 그 실행은 실패로 끝나고 다음 주기에 같은 사용자부터 이어서 처리한다.
3. `SyncCursorStore` 빈: 진행 위치(기준 시각)를 서비스 DB 에 보관한다(기준 구현: blog 의 `iam_sync_state`).
4. 설정: `doro.iam.internal-url`(Docker 내부 주소), `doro.iam.internal-token`(IAM 의 `DORO_IAM_INTERNAL_TOKENS` 에 `서비스명:토큰` 으로 등록한 값). 토큰은 환경변수로만 주고 로그에 남기지 않는다.

## 버전

서비스는 SDK 를 Doro 저장소 소스로 빌드하되(`includeBuild('../Doro')`), CI·배포는 서비스의 `.doro-sdk-ref` 에 적은 태그(`sdk-vX.Y.Z`)로 Doro 를 받는다. SDK 변경은 Doro 에 합친 뒤 새 태그를 만들고, 서비스는 그 파일을 바꾸는 PR 로 올린다. 변경 내역은 [`sdk/CHANGELOG.md`](../sdk/CHANGELOG.md).
