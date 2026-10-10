-- 2FA 시크릿을 AES-GCM 암호문(enc:v1:<base64>)으로 저장할 수 있게 컬럼을 넓힌다. 32자 시크릿의 암호문은 약 100자다.
ALTER TABLE credentials ALTER COLUMN totp_secret SET DATA TYPE VARCHAR(255);
ALTER TABLE credentials ALTER COLUMN pending_totp_secret SET DATA TYPE VARCHAR(255);
