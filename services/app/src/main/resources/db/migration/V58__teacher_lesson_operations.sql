CREATE TABLE lesson_diaries (
    id VARCHAR(255) PRIMARY KEY,
    slot_id VARCHAR(255) NOT NULL,
    session_date DATE NOT NULL,
    teacher_id VARCHAR(255) NOT NULL,
    actual_teacher_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    subject_id VARCHAR(255),
    topic VARCHAR(500),
    lesson_content VARCHAR(4000),
    homework VARCHAR(2000),
    class_note VARCHAR(2000),
    attendance_summary VARCHAR(1000),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_lesson_diary_slot_date UNIQUE (slot_id, session_date)
);
CREATE INDEX idx_lesson_diary_teacher_date ON lesson_diaries(actual_teacher_id, session_date);
CREATE INDEX idx_lesson_diary_class_date ON lesson_diaries(class_id, session_date);

CREATE TABLE timetable_change_requests (
    id VARCHAR(255) PRIMARY KEY,
    slot_id VARCHAR(255) NOT NULL,
    occurrence_date DATE NOT NULL,
    request_type VARCHAR(32) NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    original_teacher_id VARCHAR(255) NOT NULL,
    substitute_teacher_id VARCHAR(255),
    proposed_date DATE,
    proposed_period_no INTEGER,
    proposed_start_time VARCHAR(16),
    proposed_end_time VARCHAR(16),
    proposed_room_code VARCHAR(255),
    reason VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    reviewed_by VARCHAR(255),
    review_note VARCHAR(2000),
    reviewed_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_timetable_change_teacher_date
    ON timetable_change_requests(original_teacher_id, occurrence_date, status);
CREATE INDEX idx_timetable_change_substitute_date
    ON timetable_change_requests(substitute_teacher_id, occurrence_date, status);
CREATE INDEX idx_timetable_change_status_created
    ON timetable_change_requests(status, created_at);
CREATE INDEX idx_timetable_change_occurrence
    ON timetable_change_requests(slot_id, occurrence_date, status);
