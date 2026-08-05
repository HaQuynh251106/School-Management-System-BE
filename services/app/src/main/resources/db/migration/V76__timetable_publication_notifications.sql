ALTER TABLE notifications ADD COLUMN IF NOT EXISTS action_url varchar(1000);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS sent_at timestamp(6) with time zone;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS delivered_at timestamp(6) with time zone;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS read_at timestamp(6) with time zone;

UPDATE notifications
SET sent_at = COALESCE(sent_at, created_at),
    delivered_at = COALESCE(delivered_at, created_at),
    read_at = CASE WHEN read = true THEN COALESCE(read_at, created_at) ELSE read_at END;

CREATE TABLE timetable_publication_events (
    id varchar(255) NOT NULL,
    plan_id varchar(255) NOT NULL,
    semester_id varchar(255) NOT NULL,
    previous_plan_id varchar(255),
    event_type varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    reason varchar(1000) NOT NULL,
    diff_json text NOT NULL,
    change_count integer NOT NULL,
    affected_class_count integer NOT NULL,
    teacher_recipient_count integer NOT NULL,
    student_recipient_count integer NOT NULL,
    parent_recipient_count integer NOT NULL,
    total_recipient_count integer NOT NULL,
    delivered_recipient_count integer NOT NULL,
    failed_recipient_count integer NOT NULL,
    attempts integer NOT NULL,
    last_error varchar(2000),
    next_attempt_at timestamp(6) with time zone,
    created_by varchar(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    processed_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_timetable_publication_plan UNIQUE (plan_id)
);

CREATE INDEX idx_timetable_publication_outbox
    ON timetable_publication_events (status, next_attempt_at, created_at);
CREATE INDEX idx_timetable_publication_semester
    ON timetable_publication_events (semester_id, created_at);

CREATE TABLE timetable_publication_recipients (
    id varchar(255) NOT NULL,
    event_id varchar(255) NOT NULL,
    recipient_id varchar(255) NOT NULL,
    recipient_role varchar(40) NOT NULL,
    context_key varchar(500) NOT NULL,
    student_id varchar(255),
    class_id varchar(255),
    notification_id varchar(255),
    status varchar(40) NOT NULL,
    title varchar(255) NOT NULL,
    body varchar(4000) NOT NULL,
    action_url varchar(1000) NOT NULL,
    attempts integer NOT NULL,
    last_error varchar(2000),
    delivered_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_timetable_publication_recipient UNIQUE (event_id, recipient_id, context_key),
    CONSTRAINT fk_timetable_publication_recipient_event FOREIGN KEY (event_id)
        REFERENCES timetable_publication_events(id) ON DELETE CASCADE
);

CREATE INDEX idx_timetable_publication_recipient_status
    ON timetable_publication_recipients (event_id, status, created_at);
CREATE INDEX idx_timetable_publication_recipient_user
    ON timetable_publication_recipients (recipient_id, created_at);
