ALTER TABLE classes ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE subjects ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE rooms ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE teaching_assignments ADD COLUMN effective_from DATE;
ALTER TABLE teaching_assignments ADD COLUMN effective_to DATE;
ALTER TABLE teaching_assignments ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE teaching_assignments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE teaching_assignments assignment
SET effective_from = (SELECT semester.start_date FROM semesters semester WHERE semester.id = assignment.semester_id),
    effective_to = (SELECT semester.end_date FROM semesters semester WHERE semester.id = assignment.semester_id)
WHERE effective_from IS NULL OR effective_to IS NULL;

CREATE TABLE teaching_assignment_history (
    id VARCHAR(80) PRIMARY KEY,
    assignment_id VARCHAR(80) NOT NULL,
    action VARCHAR(30) NOT NULL,
    before_snapshot TEXT,
    after_snapshot TEXT,
    reason VARCHAR(500),
    changed_by VARCHAR(80),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_tah_assignment_time ON teaching_assignment_history(assignment_id, changed_at);

CREATE TABLE timetable_plans (
    id VARCHAR(80) PRIMARY KEY,
    semester_id VARCHAR(80) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version_no INTEGER,
    option_no INTEGER NOT NULL DEFAULT 1,
    quality_score INTEGER NOT NULL DEFAULT 0,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    total_assignments INTEGER NOT NULL DEFAULT 0,
    total_periods INTEGER NOT NULL DEFAULT 0,
    scheduled_periods INTEGER NOT NULL DEFAULT 0,
    unscheduled_periods INTEGER NOT NULL DEFAULT 0,
    conflict_summary TEXT,
    configuration_json TEXT,
    source_plan_id VARCHAR(80),
    created_by VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_by VARCHAR(80),
    published_at TIMESTAMP WITH TIME ZONE,
    failure_message TEXT
);
CREATE INDEX idx_timetable_plan_semester_status ON timetable_plans(semester_id, status, created_at);

CREATE TABLE timetable_plan_slots (
    id VARCHAR(80) PRIMARY KEY,
    plan_id VARCHAR(80) NOT NULL,
    assignment_id VARCHAR(80),
    class_id VARCHAR(80) NOT NULL,
    class_code VARCHAR(100),
    study_shift VARCHAR(20),
    subject_id VARCHAR(80) NOT NULL,
    subject_name VARCHAR(255),
    teacher_id VARCHAR(80) NOT NULL,
    teacher_name VARCHAR(255),
    room_code VARCHAR(100),
    day_of_week VARCHAR(10) NOT NULL,
    period_no INTEGER NOT NULL,
    start_time VARCHAR(5) NOT NULL,
    end_time VARCHAR(5) NOT NULL,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_timetable_plan_slot_plan FOREIGN KEY (plan_id) REFERENCES timetable_plans(id) ON DELETE CASCADE
);
CREATE INDEX idx_timetable_plan_slot_plan ON timetable_plan_slots(plan_id);
CREATE INDEX idx_timetable_plan_slot_teacher_time ON timetable_plan_slots(plan_id, teacher_id, day_of_week, start_time);
CREATE INDEX idx_timetable_plan_slot_class_time ON timetable_plan_slots(plan_id, class_id, day_of_week, start_time);

ALTER TABLE timetable_slots ADD COLUMN published_plan_id VARCHAR(80);
ALTER TABLE timetable_slots ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_tt_semester_time ON timetable_slots(semester_id, day_of_week, start_time, end_time);
