# 🛡️ DORO Platform & Blog 배포 및 게이트웨이 라우팅 규칙 (AGENTS.md)

본 문서는 DORO 플랫폼 마이크로서비스 생태계에서 서비스 추가, API 개발 및 서버 배포 시 발생하는 라우팅 충돌 및 누락을 원천 차단하기 위한 에이전트 필수 준수 규칙입니다.

---

## 1. 🌐 게이트웨이(Nginx) 라우팅 검증 필수 원칙

### ① 단일 게이트웨이(Port 80) 라우팅 우선순위 엄수
- 모든 서브 서비스(Blog, Portal 등)는 `doro-gateway`(포트 80)를 통해 단일 진입점으로 통합됩니다.
- Nginx의 `location` 블록 평가 우선순위(정확 매칭 `=` > 프리픽스 매칭 > 일반 매칭)를 고려하여, 광범위한 와일드카드(`/api/v1/`, `/`) 앞에 **특수 목적 API를 반드시 명시적으로 선언**해야 합니다.
  - **DORO IAM 전용 API**:
    - `/api/v1/auth/` (로그인, 회원가입, 2FA 등) -> `iam_upstream`
    - `/api/v1/sessions/` (세션 관리, 킬스위치) -> `iam_upstream`
    - `/api/v1/admin/users/` (IAM 유저 관리) -> `iam_upstream`
    - `= /api/v1/users/me/password` (비밀번호 변경) -> `iam_upstream`
  - **중앙 관제 및 스토리지 API**:
    - `/loki/` (Loki 중앙 로그 쿼리) -> `loki_upstream` (누락 시 중앙 관제 로그 빈 화면 발생)
    - `/media/` (MinIO 이미지/미디어 서빙) -> `minio_upstream`
  - **DORO Blog 전용 API**:
    - `/api/v1/` (포스트, 시리즈, 태그, 블로그 작가 프로필 등) -> `blog_api_upstream`

### ② 배포 시 게이트웨이 동기화 체크리스트
새로운 기능이나 서브 서비스를 배포할 때는 반드시 아래를 점검합니다:
1. `mini:/home/ysm/doro/gateway/nginx.conf`에 해당 서비스의 `upstream`과 `location` 프록시가 정의되어 있는가?
2. `nginx -t` 검사를 수행하고 무중단 리로드(`docker exec doro-gateway nginx -s reload`)가 정상 완료되었는가?
3. 외부 IP(`http://112.156.246.132/`)를 통한 실제 curl 호출 시 404/502/500 없이 의도한 서비스로 정확히 전달되는가?

---

## 2. 🔌 백엔드 API 메서드 호환성 및 예외 처리 원칙

1. **리소스 수정(PUT/PATCH) 유연성 확보**:
   - 사용자/엔티티 정보 수정 엔드포인트는 클라이언트 및 중앙 포털 라이브러리(Axios 등)에 따라 `PUT` 또는 `PATCH`로 호출될 수 있습니다.
   - 단일 메서드만 허용하지 말고, `@RequestMapping(value = "/me", method = {RequestMethod.PUT, RequestMethod.PATCH})`와 같이 상호 호환되도록 매핑합니다.
2. **글로벌 예외 처리(GlobalExceptionHandler) 표준화**:
   - `HttpRequestMethodNotSupportedException`은 절대 500(서버 내부 오류)으로 응답해서는 안 되며, 반드시 **`405 METHOD_NOT_ALLOWED`**로 처리합니다.
   - `HttpMessageNotReadableException`(요청 본문 누락/JSON 파싱 오류)은 **`400 BAD_REQUEST`**로 처리합니다.

---

## 3. 🖥️ 멀티 프론트엔드(SPA) 마운트 및 라우터 격리 원칙

1. **하위 경로(Sub-path) 마운트 SPA의 `basename` 검증**:
   - `/portal` 등 하위 경로에 호스팅되는 React SPA는 `BrowserRouter`에 `basename="/portal"`을 지정하여 내부 네비게이션 시 루트(`/`)로 이탈하지 않도록 방어합니다.
2. **정적 에셋(Assets) 충돌 방지**:
   - Vite/Webpack 빌드 시 `base` 설정과 게이트웨이의 라우팅이 일치하여 `/assets/index-*.js` 요청이 올바른 웹 컨테이너로 전달되는지 확인합니다.
3. **인앱 액션 우선 제공**:
   - 외부 포털 링크로 사용자를 이탈시켜 튕김을 유발하기보다, 블로그 내 모달에서 즉시 처리 가능한 원스톱 API(DORO ID 인앱 회원가입/로그인 등)를 기본 제공합니다.
