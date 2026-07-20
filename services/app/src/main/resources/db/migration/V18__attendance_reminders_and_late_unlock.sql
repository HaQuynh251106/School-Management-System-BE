CREATE TABLE attendance_session_access (
    id varchar(255) NOT NULL,
    slot_id varchar(255) NOT NULL,
    session_date date NOT NULL,
    teacher_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    reminder_sent_at timestamp(6) with time zone,
    unlock_reason varchar(1000),
    unlocked_at timestamp(6) with time zone,
    unlocked_by varchar(255),
    late_attendance_saved_at timestamp(6) with time zone,
    PRIMARY KEY (id),
    CONSTRAINT uk_att_session_slot_date UNIQUE (slot_id, session_date)
);

CREATE INDEX idx_att_session_teacher_date
    ON attendance_session_access (teacher_id, session_date);
