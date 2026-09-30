-- N3: 리프레시 토큰 해시는 유일해야 한다 (조회 키이므로 중복되면 회전 로직이 모호해진다)
CREATE UNIQUE INDEX IF NOT EXISTS uq_refresh_tokens_token_hash ON refresh_tokens(token_hash);
