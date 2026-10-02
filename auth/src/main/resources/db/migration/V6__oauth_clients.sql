-- OAuth 2.1 / OIDC 클라이언트 레지스트리.
-- redirect_uris 는 줄바꿈으로 구분하며, 인가 요청의 redirect_uri 는 이 목록과 정확히 일치해야 한다.
CREATE TABLE IF NOT EXISTS oauth_clients (
    id UUID PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    redirect_uris TEXT NOT NULL,
    allowed_scopes VARCHAR(200) NOT NULL DEFAULT 'openid profile email',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
