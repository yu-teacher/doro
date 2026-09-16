-- Doro IAM Schema V2: Add profile_image_url to users table

ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_url TEXT;
