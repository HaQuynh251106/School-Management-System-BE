ALTER TABLE timetable_schedules
    ADD COLUMN IF NOT EXISTS source_plan_summary VARCHAR(500),
    ADD COLUMN IF NOT EXISTS source_plan_snapshot TEXT;

ALTER TABLE class_lesson_progress
    ADD COLUMN IF NOT EXISTS source_plan_id VARCHAR(80),
    ADD COLUMN IF NOT EXISTS source_plan_version INTEGER;

CREATE INDEX IF NOT EXISTS idx_progress_source_plan
    ON class_lesson_progress(source_plan_id, semester_id, subject_id, class_id);
