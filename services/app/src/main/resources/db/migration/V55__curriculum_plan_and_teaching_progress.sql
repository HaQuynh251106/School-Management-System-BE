ALTER TABLE curriculum_requirements
    ADD COLUMN IF NOT EXISTS total_periods integer NOT NULL DEFAULT 0;
ALTER TABLE curriculum_requirements
    ADD COLUMN IF NOT EXISTS start_date date;
ALTER TABLE curriculum_requirements
    ADD COLUMN IF NOT EXISTS end_date date;
ALTER TABLE curriculum_requirements
    ADD COLUMN IF NOT EXISTS exam_window_start date;
ALTER TABLE curriculum_requirements
    ADD COLUMN IF NOT EXISTS exam_window_end date;
ALTER TABLE curriculum_requirements
    ADD COLUMN IF NOT EXISTS milestone varchar(1000);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS auth_type varchar(16) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE users
    ADD CONSTRAINT ck_users_auth_type CHECK (auth_type IN ('LOCAL', 'SSO'));

UPDATE curriculum_requirements
SET total_periods = CASE WHEN total_periods = 0 THEN weekly_periods * 18 ELSE total_periods END;

CREATE TABLE teaching_progress (
    id varchar(255) PRIMARY KEY,
    timetable_slot_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    class_code varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    subject_name varchar(255) NOT NULL,
    semester_id varchar(255) NOT NULL,
    teacher_id varchar(255) NOT NULL,
    teacher_name varchar(255) NOT NULL,
    lesson_date date NOT NULL,
    completed_periods integer NOT NULL,
    topic varchar(1000) NOT NULL,
    status varchar(32) NOT NULL,
    reason varchar(1000),
    makeup_date date,
    makeup_status varchar(32) NOT NULL,
    review_note varchar(1000),
    reviewed_by varchar(255),
    reviewed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_teaching_progress_slot_date UNIQUE (timetable_slot_id, lesson_date),
    CONSTRAINT ck_teaching_progress_status CHECK (status IN ('COMPLETED','CANCELLED')),
    CONSTRAINT ck_teaching_progress_makeup CHECK (makeup_status IN ('NONE','PROPOSED','APPROVED','REJECTED')),
    CONSTRAINT fk_teaching_progress_slot FOREIGN KEY (timetable_slot_id) REFERENCES timetable_slots(id) ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_progress_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_progress_subject FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_progress_semester FOREIGN KEY (semester_id) REFERENCES semesters(id) ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_progress_teacher FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_teaching_progress_scope
    ON teaching_progress (semester_id, subject_id, class_id, lesson_date);
CREATE INDEX idx_teaching_progress_teacher
    ON teaching_progress (teacher_id, lesson_date);
