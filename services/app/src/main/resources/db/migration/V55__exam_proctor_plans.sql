CREATE TABLE IF NOT EXISTS exam_proctor_plans (
    id VARCHAR(255) PRIMARY KEY,
    schedule_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    include_second_proctor BOOLEAN NOT NULL,
    room_count INTEGER NOT NULL,
    ready_room_count INTEGER NOT NULL,
    missing_assignment_count INTEGER NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    warning_summary VARCHAR(2000),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_by VARCHAR(255),
    applied_at TIMESTAMP WITH TIME ZONE,
    undone_by VARCHAR(255),
    undone_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_exam_proctor_plan_schedule FOREIGN KEY (schedule_id)
        REFERENCES exam_schedules(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_exam_proctor_plan_schedule
    ON exam_proctor_plans(schedule_id, created_at DESC);

CREATE TABLE IF NOT EXISTS exam_proctor_plan_items (
    id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    room_id VARCHAR(255) NOT NULL,
    room_code VARCHAR(255) NOT NULL,
    locked BOOLEAN NOT NULL,
    previous_proctor_one_id VARCHAR(255),
    previous_proctor_one_name VARCHAR(255),
    previous_proctor_two_id VARCHAR(255),
    previous_proctor_two_name VARCHAR(255),
    proposed_proctor_one_id VARCHAR(255),
    proposed_proctor_one_name VARCHAR(255),
    proposed_proctor_two_id VARCHAR(255),
    proposed_proctor_two_name VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    message VARCHAR(1000),
    proctor_one_duty_count INTEGER,
    proctor_two_duty_count INTEGER,
    CONSTRAINT fk_exam_proctor_plan_item_plan FOREIGN KEY (plan_id)
        REFERENCES exam_proctor_plans(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_exam_proctor_plan_item_plan
    ON exam_proctor_plan_items(plan_id);
