-- 관리자가 계정을 정지할 때 남기는 기록: 정지 시각, 정지한 관리자, 사유. 정지 중(SUSPENDED)인 계정에만 값이 있고 해제하면 지운다.
-- 정지 이력 자체는 감사 로그(SECURITY AUDIT)에 남는다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_by UUID;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(200);
