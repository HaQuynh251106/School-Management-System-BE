-- Reconciles installations created while V41-V45 source files were unavailable.
-- Every statement is idempotent so this migration is safe for both upgraded and fresh databases.

ALTER TABLE academic_years ADD COLUMN IF NOT EXISTS orientation_start_date DATE;
ALTER TABLE academic_years ADD COLUMN IF NOT EXISTS opening_date DATE;
ALTER TABLE academic_years ADD COLUMN IF NOT EXISTS instruction_weeks INTEGER NOT NULL DEFAULT 35;
ALTER TABLE academic_years ADD COLUMN IF NOT EXISTS auto_generated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE semesters ADD COLUMN IF NOT EXISTS instruction_weeks INTEGER;
ALTER TABLE semesters ADD COLUMN IF NOT EXISTS auto_generated BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE classes ADD COLUMN IF NOT EXISTS cohort_id VARCHAR(255);
ALTER TABLE classes ADD COLUMN IF NOT EXISTS planned_student_count INTEGER;
ALTER TABLE classes ADD COLUMN IF NOT EXISTS auto_generated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE class_enrollments ADD COLUMN IF NOT EXISTS cohort_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS cohort_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS main_subject_id VARCHAR(255);
ALTER TABLE exam_periods ADD COLUMN IF NOT EXISTS auto_generated BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE announcements ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS approved_by VARCHAR(255);
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS review_status VARCHAR(32) NOT NULL DEFAULT 'OPEN';
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(255);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS review_note VARCHAR(2000);
ALTER TABLE notification_delivery_logs ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE notification_delivery_logs ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(255);

ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS purpose VARCHAR(32) NOT NULL DEFAULT 'RESET_LINK';
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS family_id VARCHAR(255);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS parent_id VARCHAR(255);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS replaced_by_id VARCHAR(255);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payments ADD COLUMN IF NOT EXISTS receipt_code VARCHAR(255);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS payer_name VARCHAR(255);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS note VARCHAR(1000);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS recorded_by VARCHAR(255);
ALTER TABLE student_yearly_summaries ADD COLUMN IF NOT EXISTS conduct_note VARCHAR(1000);
ALTER TABLE student_yearly_summaries ADD COLUMN IF NOT EXISTS conduct_updated_by VARCHAR(255);
ALTER TABLE student_yearly_summaries ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS cohorts (
    id VARCHAR(255) PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    entry_year INTEGER NOT NULL,
    graduation_year INTEGER NOT NULL,
    duration_years INTEGER NOT NULL DEFAULT 3,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    entry_academic_year_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS email_change_tokens (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    new_email VARCHAR(255) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP(6) WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS algorithm_runs (
    id VARCHAR(255) PRIMARY KEY,
    algorithm_type VARCHAR(64) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    triggered_by VARCHAR(255) NOT NULL,
    assigned_role VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_count INTEGER NOT NULL,
    output_count INTEGER NOT NULL,
    summary VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS algorithm_assessments (
    id VARCHAR(255) PRIMARY KEY,
    algorithm_type VARCHAR(64) NOT NULL,
    module VARCHAR(64) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    subject_name VARCHAR(255),
    scope_id VARCHAR(255),
    score DOUBLE PRECISION NOT NULL,
    level VARCHAR(20) NOT NULL,
    explanation VARCHAR(4000) NOT NULL,
    factors VARCHAR(4000),
    recommended_action VARCHAR(2000),
    status VARCHAR(32) NOT NULL,
    assigned_role VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_by VARCHAR(255),
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    resolution_note VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS timetable_publications (
    id VARCHAR(255) PRIMARY KEY,
    class_id VARCHAR(255) NOT NULL,
    semester_id VARCHAR(255) NOT NULL,
    revision INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    change_summary VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS timetable_publication_slots (
    id VARCHAR(255) PRIMARY KEY,
    publication_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    semester_id VARCHAR(255) NOT NULL,
    day_of_week VARCHAR(32) NOT NULL,
    period_no INTEGER NOT NULL,
    start_time VARCHAR(32), end_time VARCHAR(32), subject_id VARCHAR(255), subject_name VARCHAR(255),
    teacher_id VARCHAR(255), teacher_name VARCHAR(255), room_code VARCHAR(255),
    CONSTRAINT fk_timetable_publication_slot_publication FOREIGN KEY (publication_id) REFERENCES timetable_publications(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS timetable_draft_slots (
    id VARCHAR(255) PRIMARY KEY,
    workspace_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    semester_id VARCHAR(255) NOT NULL,
    day_of_week VARCHAR(32) NOT NULL,
    period_no INTEGER NOT NULL,
    start_time VARCHAR(32), end_time VARCHAR(32), subject_id VARCHAR(255), subject_name VARCHAR(255),
    teacher_id VARCHAR(255), teacher_name VARCHAR(255), room_code VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS operation_tasks (
    id VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(4000),
    module VARCHAR(64) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    assigned_role VARCHAR(64) NOT NULL,
    assigned_to VARCHAR(255),
    source_type VARCHAR(64), source_id VARCHAR(255), due_date DATE, resolution VARCHAR(4000),
    created_by VARCHAR(255) NOT NULL, creator_name VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    priority_score INTEGER NOT NULL DEFAULT 0,
    sla_level VARCHAR(20) NOT NULL DEFAULT 'ON_TRACK',
    last_escalated_at TIMESTAMP WITH TIME ZONE,
    assigned_to_name VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS operation_task_comments (
    id VARCHAR(255) PRIMARY KEY,
    task_id VARCHAR(255) NOT NULL,
    author_id VARCHAR(255) NOT NULL,
    author_name VARCHAR(255),
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_operation_task_comment_task FOREIGN KEY (task_id) REFERENCES operation_tasks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS invoice_adjustments (
    id VARCHAR(255) PRIMARY KEY,
    invoice_id VARCHAR(255) NOT NULL,
    adjustment_type VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    previous_total BIGINT NOT NULL,
    new_total BIGINT NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_invoice_adjustment_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS finance_refunds (
    id VARCHAR(255) PRIMARY KEY,
    invoice_id VARCHAR(255) NOT NULL,
    payment_id VARCHAR(255),
    amount BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by VARCHAR(255), decided_at TIMESTAMP WITH TIME ZONE, decision_note VARCHAR(1000),
    CONSTRAINT fk_finance_refund_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS reconciliation_candidates (
    id VARCHAR(255) PRIMARY KEY,
    external_ref VARCHAR(255) NOT NULL,
    transaction_date DATE NOT NULL,
    payer_name VARCHAR(255),
    amount BIGINT NOT NULL,
    content VARCHAR(2000),
    matched_invoice_id VARCHAR(255), matched_invoice_code VARCHAR(255),
    match_score DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    explanation VARCHAR(2000) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by VARCHAR(255), decided_at TIMESTAMP WITH TIME ZONE, decision_note VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_cohorts_entry_year ON cohorts(entry_year);
CREATE INDEX IF NOT EXISTS idx_cohorts_status ON cohorts(status);
CREATE INDEX IF NOT EXISTS idx_classes_cohort ON classes(cohort_id);
CREATE INDEX IF NOT EXISTS idx_enrollments_cohort ON class_enrollments(cohort_id);
CREATE INDEX IF NOT EXISTS idx_users_cohort ON users(cohort_id);
CREATE INDEX IF NOT EXISTS idx_users_main_subject ON users(main_subject_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_email_change_user ON email_change_tokens(user_id, used_at, created_at);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_purpose ON password_reset_tokens(user_id, purpose, used_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_tokens(family_id);
CREATE INDEX IF NOT EXISTS idx_algorithm_run_history ON algorithm_runs(assigned_role, started_at);
CREATE INDEX IF NOT EXISTS idx_algorithm_assessment_subject ON algorithm_assessments(algorithm_type, subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_algorithm_assessment_work_queue ON algorithm_assessments(assigned_role, status, level, updated_at);
CREATE INDEX IF NOT EXISTS idx_timetable_publication_scope ON timetable_publications(class_id, semester_id, revision);
CREATE INDEX IF NOT EXISTS idx_timetable_publication_slot_publication ON timetable_publication_slots(publication_id, day_of_week, period_no);
CREATE INDEX IF NOT EXISTS idx_timetable_draft_workspace ON timetable_draft_slots(workspace_id, day_of_week, period_no);
CREATE INDEX IF NOT EXISTS idx_operation_task_role_status ON operation_tasks(assigned_role, status, due_date);
CREATE INDEX IF NOT EXISTS idx_operation_task_assigned_user_status ON operation_tasks(assigned_to, status, due_date);
CREATE INDEX IF NOT EXISTS idx_operation_task_source ON operation_tasks(source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_operation_task_sla ON operation_tasks(status, sla_level, due_date);
CREATE INDEX IF NOT EXISTS idx_operation_task_comment_task ON operation_task_comments(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_invoice_adjustment_invoice ON invoice_adjustments(invoice_id, created_at);
CREATE INDEX IF NOT EXISTS idx_finance_refund_invoice ON finance_refunds(invoice_id, requested_at);
CREATE INDEX IF NOT EXISTS idx_finance_refund_status ON finance_refunds(status, requested_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_reconciliation_external_ref ON reconciliation_candidates(external_ref);
CREATE INDEX IF NOT EXISTS idx_reconciliation_status ON reconciliation_candidates(status, created_at);
