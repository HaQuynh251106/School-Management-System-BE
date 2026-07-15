ALTER TABLE classes ADD COLUMN homeroom_teacher_name varchar(255);
ALTER TABLE classes ADD COLUMN homeroom_assigned_at timestamp(6) with time zone;
ALTER TABLE classes ADD COLUMN homeroom_assigned_by varchar(255);

UPDATE classes
SET homeroom_teacher_name = (
        SELECT users.full_name FROM users WHERE users.id = classes.homeroom_teacher_id
    ),
    homeroom_assigned_at = CURRENT_TIMESTAMP
WHERE homeroom_teacher_id IS NOT NULL;

CREATE INDEX idx_classes_homeroom_teacher ON classes (homeroom_teacher_id);
