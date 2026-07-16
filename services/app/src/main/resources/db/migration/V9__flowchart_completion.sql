ALTER TABLE subjects ADD COLUMN coefficient float(53) NOT NULL DEFAULT 1;

CREATE TABLE login_history (
    success boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    failure_reason varchar(500),
    id varchar(255) NOT NULL,
    ip_address varchar(255),
    user_agent varchar(1000),
    user_id varchar(255),
    username varchar(255) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_login_history_user ON login_history (user_id, created_at);
CREATE INDEX idx_login_history_username ON login_history (username, created_at);

CREATE TABLE student_yearly_summaries (
    average_score float(53),
    finalized_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    academic_year_id varchar(255) NOT NULL,
    class_id varchar(255),
    conduct_grade varchar(255),
    finalized_by varchar(255),
    id varchar(255) NOT NULL,
    missing_requirements varchar(2000),
    next_class_id varchar(255),
    promotion_status varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    student_name varchar(255),
    PRIMARY KEY (id),
    UNIQUE (academic_year_id, student_id)
);
CREATE INDEX idx_yearly_summary_year ON student_yearly_summaries (academic_year_id);
CREATE INDEX idx_yearly_summary_student ON student_yearly_summaries (student_id);
