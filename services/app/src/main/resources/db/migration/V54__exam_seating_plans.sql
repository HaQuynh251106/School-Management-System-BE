CREATE TABLE IF NOT EXISTS exam_seating_plans (
    id VARCHAR(255) PRIMARY KEY,
    exam_period_id VARCHAR(255) NOT NULL,
    schedule_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    candidate_count INTEGER NOT NULL,
    total_capacity INTEGER NOT NULL,
    assigned_count INTEGER NOT NULL,
    unassigned_count INTEGER NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    selected_room_ids VARCHAR(4000) NOT NULL,
    warning_summary VARCHAR(2000),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_by VARCHAR(255),
    applied_at TIMESTAMP WITH TIME ZONE,
    undone_by VARCHAR(255),
    undone_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_exam_seating_plan_schedule FOREIGN KEY (schedule_id)
        REFERENCES exam_schedules(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_exam_seating_plan_schedule
    ON exam_seating_plans(schedule_id, created_at DESC);

CREATE TABLE IF NOT EXISTS exam_seating_plan_items (
    id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    row_type VARCHAR(16) NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    student_name VARCHAR(255) NOT NULL,
    student_code VARCHAR(255),
    class_id VARCHAR(255) NOT NULL,
    class_code VARCHAR(255) NOT NULL,
    candidate_no VARCHAR(6) NOT NULL,
    exam_room_id VARCHAR(255),
    room_code VARCHAR(255),
    seat_no INTEGER,
    CONSTRAINT fk_exam_seating_plan_item_plan FOREIGN KEY (plan_id)
        REFERENCES exam_seating_plans(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_exam_seating_plan_item_plan
    ON exam_seating_plan_items(plan_id, row_type);
