ALTER TABLE clubs ADD COLUMN IF NOT EXISTS code VARCHAR(100);
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS approval_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS registration_start DATE;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS registration_end DATE;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE clubs SET code = id WHERE code IS NULL;
UPDATE clubs SET registration_start = DATE '2020-01-01' WHERE registration_start IS NULL;
UPDATE clubs SET registration_end = DATE '2030-12-31' WHERE registration_end IS NULL;

ALTER TABLE clubs ALTER COLUMN code SET NOT NULL;
ALTER TABLE clubs ALTER COLUMN registration_start SET NOT NULL;
ALTER TABLE clubs ALTER COLUMN registration_end SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_club_code ON clubs(code);

ALTER TABLE club_registrations ADD COLUMN IF NOT EXISTS invoice_id VARCHAR(255);
ALTER TABLE club_registrations ADD COLUMN IF NOT EXISTS decision_note VARCHAR(1000);
ALTER TABLE club_registrations ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE club_registrations ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE club_registrations ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE club_registrations SET registered_by = student_id WHERE registered_by IS NULL;
UPDATE club_registrations SET registered_at = CURRENT_TIMESTAMP WHERE registered_at IS NULL;
UPDATE club_registrations SET status = 'APPROVED' WHERE status IS NULL OR status = 'REGISTERED';

ALTER TABLE club_registrations ALTER COLUMN club_id SET NOT NULL;
ALTER TABLE club_registrations ALTER COLUMN student_id SET NOT NULL;
ALTER TABLE club_registrations ALTER COLUMN registered_by SET NOT NULL;
ALTER TABLE club_registrations ALTER COLUMN registered_at SET NOT NULL;
ALTER TABLE club_registrations ALTER COLUMN status SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_club_registration_student
    ON club_registrations(club_id, student_id);

CREATE INDEX IF NOT EXISTS idx_club_registration_club_status
    ON club_registrations(club_id, status);
CREATE INDEX IF NOT EXISTS idx_club_registration_student
    ON club_registrations(student_id);
