-- 기밀(confidential) 클라이언트: 토큰·폐기 요청에서 client_secret 으로 클라이언트를 인증한다.
-- 시크릿은 평문으로 저장하지 않고 SHA-256 해시(hex 64자)만 둔다. NULL 이면 기존처럼 공개 클라이언트(PKCE 만).
ALTER TABLE oauth_clients ADD COLUMN IF NOT EXISTS client_secret_hash VARCHAR(64);
