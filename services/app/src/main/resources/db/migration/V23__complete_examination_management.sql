CREATE TABLE exam_periods (
    id varchar(255) PRIMARY KEY,
    code varchar(100) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    academic_year_id varchar(255) NOT NULL,
    semester_id varchar(255) NOT NULL,
    grade_level varchar(50),
    start_date date NOT NULL,
    end_date date NOT NULL,
    status varchar(30) NOT NULL,
    score_entry_locked boolean NOT NULL DEFAULT false,
    confirmed_at timestamp with time zone,
    confirmed_by varchar(255),
    created_at timestamp with time zone NOT NULL,
    created_by varchar(255),
    updated_at timestamp with time zone NOT NULL
);

CREATE TABLE exam_schedules (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    subject_name varchar(255) NOT NULL,
    exam_date date NOT NULL,
    start_time varchar(20) NOT NULL,
    duration_minutes integer NOT NULL,
    notes varchar(1000),
    CONSTRAINT fk_exam_schedule_period FOREIGN KEY (exam_period_id) REFERENCES exam_periods(id) ON DELETE CASCADE
);
CREATE INDEX idx_exam_schedule_period ON exam_schedules(exam_period_id);

CREATE TABLE exam_rooms (
    id varchar(255) PRIMARY KEY,
    schedule_id varchar(255) NOT NULL,
    room_code varchar(100) NOT NULL,
    capacity integer NOT NULL,
    proctor_one_id varchar(255),
    proctor_one_name varchar(255),
    proctor_two_id varchar(255),
    proctor_two_name varchar(255),
    CONSTRAINT fk_exam_room_schedule FOREIGN KEY (schedule_id) REFERENCES exam_schedules(id) ON DELETE CASCADE,
    CONSTRAINT uk_exam_room_schedule UNIQUE(schedule_id, room_code)
);

CREATE TABLE exam_candidates (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL,
    schedule_id varchar(255) NOT NULL,
    exam_room_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    student_name varchar(255) NOT NULL,
    student_code varchar(100),
    class_id varchar(255) NOT NULL,
    class_code varchar(100) NOT NULL,
    candidate_no varchar(100) NOT NULL,
    seat_no integer NOT NULL,
    CONSTRAINT fk_exam_candidate_period FOREIGN KEY (exam_period_id) REFERENCES exam_periods(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_candidate_schedule FOREIGN KEY (schedule_id) REFERENCES exam_schedules(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_candidate_room FOREIGN KEY (exam_room_id) REFERENCES exam_rooms(id) ON DELETE CASCADE,
    CONSTRAINT uk_exam_candidate_student UNIQUE(schedule_id, student_id),
    CONSTRAINT uk_exam_candidate_no UNIQUE(schedule_id, candidate_no)
);
CREATE INDEX idx_exam_candidate_class ON exam_candidates(exam_period_id, class_id);

CREATE TABLE exam_results (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL,
    schedule_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    score double precision,
    status varchar(30) NOT NULL,
    note varchar(1000),
    recorded_at timestamp with time zone,
    recorded_by varchar(255),
    updated_at timestamp with time zone,
    updated_by varchar(255),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_exam_result_period FOREIGN KEY (exam_period_id) REFERENCES exam_periods(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_result_schedule FOREIGN KEY (schedule_id) REFERENCES exam_schedules(id) ON DELETE CASCADE,
    CONSTRAINT uk_exam_result_student_subject UNIQUE(exam_period_id, student_id, subject_id)
);
CREATE INDEX idx_exam_result_period_student ON exam_results(exam_period_id, student_id);

CREATE TABLE exam_review_requests (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL,
    result_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    student_name varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    subject_name varchar(255) NOT NULL,
    original_score double precision,
    reason varchar(2000) NOT NULL,
    status varchar(30) NOT NULL,
    resolution varchar(2000),
    resolved_score double precision,
    requested_at timestamp with time zone NOT NULL,
    requested_by varchar(255) NOT NULL,
    resolved_at timestamp with time zone,
    resolved_by varchar(255),
    CONSTRAINT fk_exam_review_period FOREIGN KEY (exam_period_id) REFERENCES exam_periods(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_review_result FOREIGN KEY (result_id) REFERENCES exam_results(id) ON DELETE CASCADE
);
CREATE INDEX idx_exam_review_period_status ON exam_review_requests(exam_period_id, status);

CREATE TABLE exam_score_adjustments (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL,
    result_id varchar(255) NOT NULL,
    review_request_id varchar(255),
    old_score double precision,
    new_score double precision,
    reason varchar(2000) NOT NULL,
    adjusted_at timestamp with time zone NOT NULL,
    adjusted_by varchar(255) NOT NULL,
    CONSTRAINT fk_exam_adjustment_period FOREIGN KEY (exam_period_id) REFERENCES exam_periods(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_adjustment_result FOREIGN KEY (result_id) REFERENCES exam_results(id) ON DELETE CASCADE
);
CREATE INDEX idx_exam_adjustment_period ON exam_score_adjustments(exam_period_id, adjusted_at);
