CREATE TABLE report_cards (
    id VARCHAR(255) PRIMARY KEY,
    academic_year_id VARCHAR(255) NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    homeroom_teacher_id VARCHAR(255),
    homeroom_comment VARCHAR(2000),
    status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
    verification_code VARCHAR(64) NOT NULL,
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    submitted_by VARCHAR(255),
    approved_at TIMESTAMP(6) WITH TIME ZONE,
    approved_by VARCHAR(255),
    locked_at TIMESTAMP(6) WITH TIME ZONE,
    locked_by VARCHAR(255),
    published_at TIMESTAMP(6) WITH TIME ZONE,
    published_by VARCHAR(255),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_report_card_year_student UNIQUE(academic_year_id, student_id)
);
CREATE INDEX idx_report_card_year_status ON report_cards(academic_year_id, status);
CREATE INDEX idx_report_card_class ON report_cards(class_id, status);

CREATE TABLE report_card_audits (
    id VARCHAR(255) PRIMARY KEY,
    report_card_id VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    from_status VARCHAR(64),
    to_status VARCHAR(64),
    note VARCHAR(2000),
    actor_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_report_card_audit_card ON report_card_audits(report_card_id, created_at);

CREATE TABLE gradebook_completion_audits (
    id VARCHAR(255) PRIMARY KEY,
    semester_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    note VARCHAR(2000),
    actor_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_gradebook_completion_audit_scope
    ON gradebook_completion_audits(semester_id, class_id, subject_id, created_at);
