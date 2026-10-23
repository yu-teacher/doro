-- 소셜 로그인(GitHub, Google 등): 외부 계정 연결 표와, 이메일 소유가 확인된 계정 표시, 비밀번호 없는 계정 표시.

-- 이메일 소유 확인 여부. 기존 비밀번호 가입은 이메일 인증을 하지 않았으므로 모두 FALSE 로 시작한다.
-- 소셜 공급자가 이메일 소유를 확인해 준 계정만 TRUE.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- 소셜로 가입해 비밀번호를 정한 적이 없는 계정은 아무도 모르는 무작위 해시를 두고 FALSE 로 표시한다(비밀번호 로그인 불가).
ALTER TABLE credentials ADD COLUMN password_set BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE social_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_user_id VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_social_identities_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_social_identity_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_social_identity_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX idx_social_identities_user_id ON social_identities(user_id);
