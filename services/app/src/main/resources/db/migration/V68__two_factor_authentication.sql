CREATE TABLE two_factor_credentials (
    user_id VARCHAR(255) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_ciphertext TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    enabled_at TIMESTAMP WITH TIME ZONE,
    last_used_counter BIGINT
);
