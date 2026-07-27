ALTER TABLE notification_delivery_logs ADD COLUMN IF NOT EXISTS title varchar(255);
ALTER TABLE notification_delivery_logs ADD COLUMN IF NOT EXISTS payload varchar(4000);
ALTER TABLE notification_delivery_logs ADD COLUMN IF NOT EXISTS next_attempt_at timestamp(6) with time zone;
ALTER TABLE notification_delivery_logs ADD COLUMN IF NOT EXISTS updated_at timestamp(6) with time zone;

UPDATE notification_delivery_logs
SET updated_at = created_at
WHERE updated_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_delivery_retry
    ON notification_delivery_logs (status, next_attempt_at, attempts);
