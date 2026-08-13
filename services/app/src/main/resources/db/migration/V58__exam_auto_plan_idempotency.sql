ALTER TABLE exam_schedules ADD COLUMN IF NOT EXISTS plan_operation_key VARCHAR(120);
ALTER TABLE exam_schedules ADD COLUMN IF NOT EXISTS plan_request_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_exam_schedule_plan_operation
    ON exam_schedules(plan_operation_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_exam_schedule_plan_subject
    ON exam_schedules(plan_operation_key, subject_id);
