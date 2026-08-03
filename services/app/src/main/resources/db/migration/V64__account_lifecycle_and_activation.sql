ALTER TABLE users ADD COLUMN IF NOT EXISTS activation_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS activation_sent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS activation_completed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS purpose VARCHAR(32) NOT NULL DEFAULT 'RESET_LINK';
ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_users_account_lifecycle
    ON users(activation_status, password_change_required, status);
CREATE INDEX IF NOT EXISTS idx_password_tokens_user_purpose_active
    ON password_reset_tokens(user_id, purpose, used_at, expires_at);
