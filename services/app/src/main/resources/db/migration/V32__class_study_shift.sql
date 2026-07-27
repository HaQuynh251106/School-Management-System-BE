ALTER TABLE classes
    ADD COLUMN study_shift varchar(20) NOT NULL DEFAULT 'MORNING';

UPDATE classes c
SET study_shift = 'AFTERNOON'
WHERE EXISTS (
    SELECT 1
    FROM timetable_slots t
    WHERE t.class_id = c.id
      AND t.start_time IS NOT NULL
      AND t.start_time >= '12:00'
)
AND NOT EXISTS (
    SELECT 1
    FROM timetable_slots t
    WHERE t.class_id = c.id
      AND t.start_time IS NOT NULL
      AND t.start_time < '12:00'
);

ALTER TABLE classes
    ADD CONSTRAINT ck_classes_study_shift
    CHECK (study_shift IN ('MORNING', 'AFTERNOON'));

CREATE INDEX idx_classes_study_shift ON classes(study_shift);
