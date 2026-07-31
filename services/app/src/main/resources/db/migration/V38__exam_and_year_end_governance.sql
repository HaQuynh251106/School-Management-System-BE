CREATE TABLE IF NOT EXISTS exam_result_change_logs (
    id VARCHAR(255) PRIMARY KEY, exam_period_id VARCHAR(255) NOT NULL, result_id VARCHAR(255) NOT NULL,
    schedule_id VARCHAR(255) NOT NULL, student_id VARCHAR(255) NOT NULL, subject_id VARCHAR(255) NOT NULL,
    previous_score DOUBLE PRECISION, new_score DOUBLE PRECISION, previous_note VARCHAR(1000), new_note VARCHAR(1000),
    change_type VARCHAR(40) NOT NULL, reason VARCHAR(2000), changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    changed_by VARCHAR(255) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_exam_result_log_period ON exam_result_change_logs (exam_period_id, changed_at);
CREATE INDEX IF NOT EXISTS idx_exam_result_log_result ON exam_result_change_logs (result_id, changed_at);
ALTER TABLE exam_review_requests ADD COLUMN IF NOT EXISTS evidence_file_id VARCHAR(255);
ALTER TABLE exam_review_requests ADD COLUMN IF NOT EXISTS evidence_file_name VARCHAR(255);

CREATE TABLE IF NOT EXISTS year_rollover_plans (
    id VARCHAR(255) PRIMARY KEY, source_academic_year_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE, next_year_code VARCHAR(255) NOT NULL,
    next_year_name VARCHAR(255), start_date DATE NOT NULL, end_date DATE NOT NULL,
    create_intake_classes BOOLEAN NOT NULL DEFAULT TRUE, activate_next_year BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(40) NOT NULL, preview_snapshot TEXT, expected_promoted INTEGER, expected_retained INTEGER,
    expected_graduated INTEGER, created_at TIMESTAMP WITH TIME ZONE NOT NULL, created_by VARCHAR(255) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE, approved_by VARCHAR(255), executed_at TIMESTAMP WITH TIME ZONE,
    executed_by VARCHAR(255), cancelled_at TIMESTAMP WITH TIME ZONE, cancelled_by VARCHAR(255),
    cancellation_reason VARCHAR(1000), target_academic_year_id VARCHAR(255), execution_result TEXT
);
CREATE INDEX IF NOT EXISTS idx_rollover_plan_year ON year_rollover_plans (source_academic_year_id, created_at);

CREATE TABLE IF NOT EXISTS academic_documents (
    id VARCHAR(255) PRIMARY KEY, document_type VARCHAR(40) NOT NULL, source_id VARCHAR(255) NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL, student_id VARCHAR(255) NOT NULL, student_name VARCHAR(255) NOT NULL,
    student_code VARCHAR(255), class_code VARCHAR(255), revision INTEGER NOT NULL,
    verification_code VARCHAR(32) NOT NULL UNIQUE, content_hash VARCHAR(64) NOT NULL, content BYTEA NOT NULL,
    status VARCHAR(40) NOT NULL, issued_at TIMESTAMP WITH TIME ZONE NOT NULL, issued_by VARCHAR(255) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_academic_document_student ON academic_documents (student_id, academic_year_id, issued_at);
CREATE INDEX IF NOT EXISTS idx_academic_document_source ON academic_documents (document_type, source_id, student_id);
