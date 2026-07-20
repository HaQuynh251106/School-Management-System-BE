ALTER TABLE assignments ADD COLUMN updated_at timestamp(6) with time zone;
UPDATE assignments SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE assignment_submissions ADD COLUMN resubmission_allowed boolean NOT NULL DEFAULT false;
ALTER TABLE assignment_submissions ADD COLUMN attempt_number integer NOT NULL DEFAULT 1;
