ALTER TABLE grades ADD COLUMN created_at timestamp with time zone;
ALTER TABLE grades ADD COLUMN created_by varchar(255);
ALTER TABLE grades ADD COLUMN updated_at timestamp with time zone;
ALTER TABLE grades ADD COLUMN updated_by varchar(255);
ALTER TABLE grades ADD COLUMN version bigint NOT NULL DEFAULT 0;

UPDATE grades
SET created_at = recorded_at,
    updated_at = recorded_at
WHERE created_at IS NULL;

ALTER TABLE grade_change_logs ADD COLUMN action varchar(32);
UPDATE grade_change_logs SET action = 'UPDATE' WHERE action IS NULL;
