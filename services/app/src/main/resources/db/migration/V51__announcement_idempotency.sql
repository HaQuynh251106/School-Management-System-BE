ALTER TABLE announcements
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uk_announcement_creator_idempotency
    ON announcements (created_by, idempotency_key);
