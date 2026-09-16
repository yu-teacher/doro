-- Doro IAM Schema V3: Add role to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(30) NOT NULL DEFAULT 'USER';
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);
