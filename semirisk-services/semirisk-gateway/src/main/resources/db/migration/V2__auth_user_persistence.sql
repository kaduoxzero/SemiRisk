ALTER TABLE system_user
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(128) NOT NULL DEFAULT '' AFTER username,
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255) NULL AFTER email,
    ADD COLUMN IF NOT EXISTS last_login_at DATETIME NULL AFTER status,
    ADD COLUMN IF NOT EXISTS password_updated_at DATETIME NULL AFTER last_login_at;

UPDATE system_user
SET display_name = username
WHERE display_name = '';

DELETE FROM system_user
WHERE username IN ('admin', 'analyst', 'ops')
  AND (password_hash IS NULL OR password_hash = '');
