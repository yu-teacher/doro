-- 자사(first-party) 클라이언트 표시. true 이면 포털 동의 화면을 생략하고 로그인 직후 바로 인가 코드를 발급한다.
-- 제3자 앱은 기본값(false)이라 기존처럼 동의 화면을 거친다.
ALTER TABLE oauth_clients ADD COLUMN IF NOT EXISTS first_party BOOLEAN NOT NULL DEFAULT FALSE;
