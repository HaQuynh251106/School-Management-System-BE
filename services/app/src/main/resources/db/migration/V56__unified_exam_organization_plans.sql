ALTER TABLE exam_candidates ADD COLUMN IF NOT EXISTS desk_no INTEGER;
ALTER TABLE exam_candidates ADD COLUMN IF NOT EXISTS seat_position INTEGER;

CREATE TABLE IF NOT EXISTS exam_organization_plans (
    id VARCHAR(255) PRIMARY KEY,
    schedule_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    max_candidates_per_room INTEGER NOT NULL,
    students_per_desk INTEGER NOT NULL,
    include_second_proctor BOOLEAN NOT NULL DEFAULT FALSE,
    candidate_count INTEGER NOT NULL,
    room_count INTEGER NOT NULL,
    effective_capacity INTEGER NOT NULL,
    assigned_count INTEGER NOT NULL,
    missing_assignment_count INTEGER NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    warning_summary VARCHAR(2000),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_by VARCHAR(255),
    applied_at TIMESTAMP WITH TIME ZONE,
    undone_by VARCHAR(255),
    undone_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_exam_organization_plan_schedule FOREIGN KEY (schedule_id)
        REFERENCES exam_schedules(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_exam_organization_plan_schedule
    ON exam_organization_plans(schedule_id, created_at DESC);

CREATE TABLE IF NOT EXISTS exam_organization_plan_rooms (
    id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    row_type VARCHAR(16) NOT NULL,
    room_id VARCHAR(255) NOT NULL,
    room_code VARCHAR(255) NOT NULL,
    physical_capacity INTEGER NOT NULL,
    effective_capacity INTEGER NOT NULL,
    proctor_one_id VARCHAR(255),
    proctor_one_name VARCHAR(255),
    proctor_two_id VARCHAR(255),
    proctor_two_name VARCHAR(255),
    candidate_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_exam_organization_room_plan FOREIGN KEY (plan_id)
        REFERENCES exam_organization_plans(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_exam_organization_room_plan
    ON exam_organization_plan_rooms(plan_id, row_type);

CREATE TABLE IF NOT EXISTS exam_organization_plan_candidates (
    id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    row_type VARCHAR(16) NOT NULL,
    candidate_id VARCHAR(255) NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    student_name VARCHAR(255) NOT NULL,
    student_code VARCHAR(255),
    class_id VARCHAR(255) NOT NULL,
    class_code VARCHAR(255) NOT NULL,
    candidate_no VARCHAR(6) NOT NULL,
    room_id VARCHAR(255) NOT NULL,
    room_code VARCHAR(255) NOT NULL,
    seat_no INTEGER NOT NULL,
    desk_no INTEGER NOT NULL,
    seat_position INTEGER NOT NULL,
    CONSTRAINT fk_exam_organization_candidate_plan FOREIGN KEY (plan_id)
        REFERENCES exam_organization_plans(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_exam_organization_candidate_plan
    ON exam_organization_plan_candidates(plan_id, row_type);
