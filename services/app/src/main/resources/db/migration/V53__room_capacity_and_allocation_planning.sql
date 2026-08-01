ALTER TABLE rooms ADD COLUMN IF NOT EXISTS room_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS equipment_tags VARCHAR(1000);
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS home_room_eligible BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS notes VARCHAR(1000);

UPDATE rooms
SET room_type = CASE
    WHEN UPPER(code) LIKE 'LAB%' OR LOWER(name) LIKE '%thí nghiệm%' THEN 'LAB'
    WHEN LOWER(name) LIKE '%tin học%' OR LOWER(name) LIKE '%máy tính%' THEN 'COMPUTER'
    WHEN LOWER(name) LIKE '%thể chất%' OR LOWER(name) LIKE '%đa năng%' THEN 'SPORT'
    ELSE room_type
END,
home_room_eligible = CASE
    WHEN UPPER(code) LIKE 'LAB%' OR LOWER(name) LIKE '%thí nghiệm%'
      OR LOWER(name) LIKE '%tin học%' OR LOWER(name) LIKE '%máy tính%'
      OR LOWER(name) LIKE '%thể chất%' OR LOWER(name) LIKE '%đa năng%' THEN FALSE
    ELSE home_room_eligible
END;

CREATE TABLE IF NOT EXISTS subject_room_requirements (
    id VARCHAR(80) PRIMARY KEY,
    subject_id VARCHAR(64) NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    room_type VARCHAR(32) NOT NULL,
    required_equipment VARCHAR(1000),
    weekly_periods INTEGER NOT NULL DEFAULT 0,
    mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    priority INTEGER NOT NULL DEFAULT 50,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_subject_room_requirement UNIQUE(subject_id, room_type),
    CONSTRAINT ck_subject_room_weekly_periods CHECK (weekly_periods >= 0 AND weekly_periods <= 20),
    CONSTRAINT ck_subject_room_priority CHECK (priority >= 0 AND priority <= 100)
);

CREATE TABLE IF NOT EXISTS room_allocation_plans (
    id VARCHAR(80) PRIMARY KEY,
    academic_year_id VARCHAR(64) NOT NULL REFERENCES academic_years(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    total_classes INTEGER NOT NULL DEFAULT 0,
    assigned_classes INTEGER NOT NULL DEFAULT 0,
    unassigned_classes INTEGER NOT NULL DEFAULT 0,
    morning_classes INTEGER NOT NULL DEFAULT 0,
    afternoon_classes INTEGER NOT NULL DEFAULT 0,
    configuration_json TEXT,
    warning_summary TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by VARCHAR(64),
    applied_at TIMESTAMP,
    undone_by VARCHAR(64),
    undone_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS room_allocation_plan_items (
    id VARCHAR(80) PRIMARY KEY,
    plan_id VARCHAR(80) NOT NULL REFERENCES room_allocation_plans(id) ON DELETE CASCADE,
    class_id VARCHAR(64) NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    class_code VARCHAR(64) NOT NULL,
    student_count INTEGER NOT NULL DEFAULT 0,
    class_capacity INTEGER NOT NULL,
    previous_shift VARCHAR(24),
    previous_room_id VARCHAR(64),
    previous_room_code VARCHAR(64),
    proposed_shift VARCHAR(24),
    proposed_room_id VARCHAR(64),
    proposed_room_code VARCHAR(64),
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL,
    message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_room_allocation_plan_class UNIQUE(plan_id, class_id)
);

CREATE INDEX IF NOT EXISTS idx_room_allocation_plans_year
    ON room_allocation_plans(academic_year_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_room_allocation_items_plan
    ON room_allocation_plan_items(plan_id, status, class_code);
CREATE INDEX IF NOT EXISTS idx_rooms_planning
    ON rooms(status, home_room_eligible, supports_morning, supports_afternoon, capacity);

