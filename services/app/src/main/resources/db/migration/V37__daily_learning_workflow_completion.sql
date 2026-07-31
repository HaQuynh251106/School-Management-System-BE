ALTER TABLE attendance_records ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE attendance_records ADD COLUMN updated_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE attendance_records ADD COLUMN updated_by VARCHAR(255);
UPDATE attendance_records SET updated_at = CURRENT_TIMESTAMP WHERE updated_at IS NULL;

ALTER TABLE attendance_session_access ADD COLUMN save_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE attendance_session_access ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE attendance_session_access ADD COLUMN finalized_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE attendance_session_access ADD COLUMN finalized_by VARCHAR(255);
ALTER TABLE attendance_session_access ADD COLUMN unlock_expires_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE attendance_session_access ADD COLUMN cancelled_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE attendance_session_access ADD COLUMN cancelled_by VARCHAR(255);
ALTER TABLE attendance_session_access ADD COLUMN cancellation_reason VARCHAR(1000);

UPDATE attendance_session_access access
SET save_state = 'FINAL', finalized_at = COALESCE(access.late_attendance_saved_at, access.unlocked_at, CURRENT_TIMESTAMP)
WHERE EXISTS (SELECT 1 FROM attendance_records record WHERE record.slot_id = access.slot_id AND record.date = access.session_date);

CREATE TABLE attendance_change_logs (
    id VARCHAR(255) PRIMARY KEY,
    attendance_record_id VARCHAR(255) NOT NULL,
    slot_id VARCHAR(255) NOT NULL,
    session_date DATE NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    action VARCHAR(30) NOT NULL,
    old_status VARCHAR(40), new_status VARCHAR(40), old_note VARCHAR(255), new_note VARCHAR(255),
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    session_revision BIGINT NOT NULL,
    save_state VARCHAR(20) NOT NULL
);
CREATE INDEX idx_att_change_session ON attendance_change_logs(slot_id, session_date, changed_at);
CREATE INDEX idx_att_change_record ON attendance_change_logs(attendance_record_id, changed_at);

CREATE TABLE gradebook_locks (
    id VARCHAR(255) PRIMARY KEY,
    semester_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(1000),
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_gradebook_lock_scope UNIQUE(semester_id, class_id, subject_id)
);
CREATE INDEX idx_gradebook_lock_semester ON gradebook_locks(semester_id, class_id);

CREATE TABLE grade_category_configurations (
    id VARCHAR(255) PRIMARY KEY,
    semester_id VARCHAR(255) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    category_code VARCHAR(255) NOT NULL,
    category_name VARCHAR(255) NOT NULL,
    weight DOUBLE PRECISION NOT NULL,
    required_count INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_grade_category_scope UNIQUE(semester_id, subject_id, category_code)
);
CREATE INDEX idx_grade_category_scope ON grade_category_configurations(semester_id, subject_id);

ALTER TABLE assignments ADD COLUMN rubric_json TEXT;
ALTER TABLE assignments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE assignment_submissions ADD COLUMN rubric_scores_json TEXT;
ALTER TABLE assignment_submissions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE assignment_attachments (
    id VARCHAR(255) PRIMARY KEY, assignment_id VARCHAR(255) NOT NULL, file_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL, display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_assignment_attachment_file UNIQUE(assignment_id, file_id)
);
CREATE INDEX idx_assignment_attachment_assignment ON assignment_attachments(assignment_id, display_order);
CREATE INDEX idx_assignment_attachment_file ON assignment_attachments(file_id);
INSERT INTO assignment_attachments(id, assignment_id, file_id, file_name, display_order)
SELECT CONCAT('asga-', id), id, attachment_file_id, COALESCE(attachment_name, 'Tệp bài tập'), 0 FROM assignments WHERE attachment_file_id IS NOT NULL;

CREATE TABLE submission_attachments (
    id VARCHAR(255) PRIMARY KEY, submission_id VARCHAR(255) NOT NULL, file_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL, display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_submission_attachment_file UNIQUE(submission_id, file_id)
);
CREATE INDEX idx_submission_attachment_submission ON submission_attachments(submission_id, display_order);
CREATE INDEX idx_submission_attachment_file ON submission_attachments(file_id);
INSERT INTO submission_attachments(id, submission_id, file_id, file_name, display_order)
SELECT CONCAT('suba-', id), id, attachment_file_id, COALESCE(attachment_name, 'Tệp bài làm'), 0 FROM assignment_submissions WHERE attachment_file_id IS NOT NULL;

CREATE TABLE submission_attempt_attachments (
    id VARCHAR(255) PRIMARY KEY, attempt_id VARCHAR(255) NOT NULL, file_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL, display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_attempt_attachment_file UNIQUE(attempt_id, file_id)
);
CREATE INDEX idx_attempt_multi_attachment ON submission_attempt_attachments(attempt_id, display_order);
CREATE INDEX idx_attempt_multi_file ON submission_attempt_attachments(file_id);
INSERT INTO submission_attempt_attachments(id, attempt_id, file_id, file_name, display_order)
SELECT CONCAT('atta-', id), id, attachment_file_id, COALESCE(attachment_name, 'Tệp bài làm'), 0 FROM assignment_submission_attempts WHERE attachment_file_id IS NOT NULL;

CREATE TABLE assignment_lifecycle_logs (
    id VARCHAR(255) PRIMARY KEY, assignment_id VARCHAR(255) NOT NULL, action VARCHAR(40) NOT NULL,
    old_status VARCHAR(40), new_status VARCHAR(40), detail VARCHAR(2000), changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_assignment_lifecycle ON assignment_lifecycle_logs(assignment_id, changed_at);
