-- 회원탈퇴: 유예 기간(PENDING_DELETION) 시작 시각. 유예가 끝나면 개인정보를 익명화하고 DELETED 로 바꾼다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMP WITH TIME ZONE;

-- 영구 익명화된 시각. 서브 서비스(블로그 등)가 '어떤 사용자가 탈퇴했는지'를 이 시각 기준으로 증분 조회한다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at);

-- 영구 처리 대상(유예 만료) 조회용. H2(테스트)와 PostgreSQL 이 모두 실행하는 스크립트라 부분 인덱스는 쓰지 않는다.
CREATE INDEX IF NOT EXISTS idx_users_status_deletion_requested
    ON users (status, deletion_requested_at);
