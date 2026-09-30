-- 2FA 등록 확정 전 시크릿을 활성 시크릿(totp_secret)과 분리해 보관한다.
-- setup 만 호출하고 이탈해도 계정이 2FA 요구 상태로 잠기지 않는다.
ALTER TABLE credentials ADD COLUMN IF NOT EXISTS pending_totp_secret VARCHAR(64);
