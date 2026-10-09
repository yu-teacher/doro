-- 이메일은 가입 시 trim + 소문자로 정규화해 저장한다(EmailNormalizer). 그 규칙을 DB 가 강제해서
-- 기존 UNIQUE(email)이 곧 대소문자 무시 유일성이 되게 한다. 동시에 가입하는 두 요청이
-- 서비스의 중복 검사를 함께 통과해도 DB 가 한쪽을 막는다.
-- 함수 기반 인덱스(lower(email))는 H2(테스트)가 지원하지 않아, 두 DB 가 모두 실행하는 CHECK 로 보장한다.
-- 적용 전 운영 데이터에 대문자 이메일·대소문자만 다른 중복이 없음을 확인했다(2026-10-09).
ALTER TABLE users ADD CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email));
