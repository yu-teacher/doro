-- OAuth 클라이언트(외부 앱 포함)에 발급한 세션이 어떤 scope 로 허용됐는지 기억한다.
-- userinfo 가 허용된 scope 만큼만 돌려주고, 리프레시로 새 액세스 토큰을 낼 때도 같은 scope 를 이어 가기 위한 값이다. 일반 로그인 세션은 NULL.
ALTER TABLE user_sessions ADD COLUMN oauth_scope VARCHAR(255);
