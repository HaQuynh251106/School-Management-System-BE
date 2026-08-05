-- Development schema patch. Class codes repeat across school years, but not within one year.
ALTER TABLE IF EXISTS classes DROP CONSTRAINT IF EXISTS uk_ivcaxrbwnp0dosg2gj4i3sxpq;
CREATE UNIQUE INDEX IF NOT EXISTS uk_classes_academic_year_code
    ON classes (academic_year_id, code);
-- P6.2: persisted yearly decisions and immutable grade locks after finalization.
CREATE TABLE IF NOT EXISTS student_yearly_summaries (
    id varchar(255) PRIMARY KEY,
    academic_year_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    student_code varchar(255),
    student_name varchar(255),
    yearly_average double precision,
    attendance_rate double precision,
    conduct_grade varchar(24),
    result varchar(32) NOT NULL,
    status varchar(24) NOT NULL,
    reason varchar(1000),
    reviewed_by varchar(255),
    reviewed_at timestamptz,
    finalized_by varchar(255),
    finalized_at timestamptz,
    progression_status varchar(32),
    next_class_id varchar(255),
    progressed_by varchar(255),
    progressed_at timestamptz,
    semester_results_json text,
    subject_results_json text,
    updated_at timestamptz
);
ALTER TABLE student_yearly_summaries
    ADD COLUMN IF NOT EXISTS conduct_grade varchar(24);
ALTER TABLE student_yearly_summaries
    ADD COLUMN IF NOT EXISTS progression_status varchar(32);
ALTER TABLE student_yearly_summaries
    ADD COLUMN IF NOT EXISTS next_class_id varchar(255);
ALTER TABLE student_yearly_summaries
    ADD COLUMN IF NOT EXISTS progressed_by varchar(255);
ALTER TABLE student_yearly_summaries
    ADD COLUMN IF NOT EXISTS progressed_at timestamptz;
ALTER TABLE student_yearly_summaries
    ADD COLUMN IF NOT EXISTS semester_results_json text;
ALTER TABLE student_yearly_summaries
    ADD COLUMN IF NOT EXISTS subject_results_json text;
CREATE UNIQUE INDEX IF NOT EXISTS uk_yearly_summary_student_year
    ON student_yearly_summaries (academic_year_id, student_id);
CREATE INDEX IF NOT EXISTS idx_yearly_summary_class
    ON student_yearly_summaries (academic_year_id, class_id);
CREATE INDEX IF NOT EXISTS idx_yearly_summary_status
    ON student_yearly_summaries (status);
ALTER TABLE student_yearly_summaries DROP CONSTRAINT IF EXISTS ck_yearly_summary_result;
ALTER TABLE student_yearly_summaries ADD CONSTRAINT ck_yearly_summary_result
    CHECK (result IN ('PROMOTED', 'RETAINED', 'GRADUATED', 'ELIGIBLE_FOR_GRADUATION', 'INCOMPLETE', 'PENDING_REVIEW'));
ALTER TABLE student_yearly_summaries DROP CONSTRAINT IF EXISTS ck_yearly_summary_status;
ALTER TABLE student_yearly_summaries ADD CONSTRAINT ck_yearly_summary_status
    CHECK (status IN ('DRAFT', 'FINALIZED'));

CREATE TABLE IF NOT EXISTS academic_result_locks (
    id varchar(255) PRIMARY KEY,
    academic_year_id varchar(255) NOT NULL,
    semester_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    locked_by varchar(255) NOT NULL,
    locked_at timestamptz NOT NULL,
    reason varchar(500)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_result_lock_class_semester
    ON academic_result_locks (class_id, semester_id);
CREATE INDEX IF NOT EXISTS idx_result_lock_year_class
    ON academic_result_locks (academic_year_id, class_id);

CREATE TABLE IF NOT EXISTS academic_promotion_policies (
    id varchar(255) PRIMARY KEY,
    academic_year_id varchar(255) NOT NULL UNIQUE,
    minimum_yearly_average double precision NOT NULL,
    minimum_conduct_grade varchar(24) NOT NULL,
    subject_minimum_score double precision NOT NULL,
    maximum_subjects_below_minimum integer NOT NULL,
    minimum_attendance_rate double precision,
    updated_by varchar(255),
    updated_at timestamptz
);

CREATE TABLE IF NOT EXISTS student_class_enrollments (
    id varchar(255) PRIMARY KEY,
    academic_year_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    student_code varchar(255),
    student_name varchar(255),
    source_academic_year_id varchar(255) NOT NULL,
    source_class_id varchar(255) NOT NULL,
    source_summary_id varchar(255) NOT NULL,
    enrollment_type varchar(24) NOT NULL,
    status varchar(24) NOT NULL,
    enrolled_by varchar(255) NOT NULL,
    enrolled_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_student_enrollment_year
    ON student_class_enrollments (academic_year_id, student_id);
CREATE INDEX IF NOT EXISTS idx_student_enrollment_class
    ON student_class_enrollments (academic_year_id, class_id);
CREATE INDEX IF NOT EXISTS idx_student_enrollment_source
    ON student_class_enrollments (source_academic_year_id, source_class_id);
ALTER TABLE student_class_enrollments
    ADD COLUMN IF NOT EXISTS reverted_by varchar(255);
ALTER TABLE student_class_enrollments
    ADD COLUMN IF NOT EXISTS reverted_at timestamptz;
ALTER TABLE student_class_enrollments
    ADD COLUMN IF NOT EXISTS revert_reason text;

CREATE TABLE IF NOT EXISTS year_result_publications (
    id varchar(255) PRIMARY KEY,
    academic_year_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    status varchar(32) NOT NULL,
    student_count integer NOT NULL DEFAULT 0,
    published_by varchar(255),
    published_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT uk_year_result_publication_class
        UNIQUE (academic_year_id, class_id)
);
CREATE INDEX IF NOT EXISTS idx_year_result_publication_status
    ON year_result_publications (academic_year_id, status);
ALTER TABLE year_result_publications
    ADD COLUMN IF NOT EXISTS publication_version integer NOT NULL DEFAULT 0;
ALTER TABLE year_result_publications
    ADD COLUMN IF NOT EXISTS last_publish_reason text;
ALTER TABLE year_result_publications
    ADD COLUMN IF NOT EXISTS withdrawn_by varchar(255);
ALTER TABLE year_result_publications
    ADD COLUMN IF NOT EXISTS withdrawn_at timestamptz;
ALTER TABLE year_result_publications
    ADD COLUMN IF NOT EXISTS withdrawal_reason text;
UPDATE year_result_publications
SET publication_version = 1
WHERE status IN ('PUBLISHED', 'WITHDRAWN')
  AND COALESCE(publication_version, 0) = 0;
UPDATE year_result_publications
SET publication_version = 0
WHERE publication_version IS NULL;
ALTER TABLE year_result_publications
    ALTER COLUMN publication_version SET DEFAULT 0;
ALTER TABLE year_result_publications
    ALTER COLUMN publication_version SET NOT NULL;

ALTER TABLE classes ADD COLUMN IF NOT EXISTS max_students integer DEFAULT 45;
UPDATE classes SET max_students = 45 WHERE max_students IS NULL;

ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS late_minutes integer;

CREATE TABLE IF NOT EXISTS attendance_excuse_requests (
    id varchar(255) PRIMARY KEY,
    attendance_record_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    requested_by varchar(255) NOT NULL,
    requester_role varchar(32) NOT NULL,
    reason varchar(1000) NOT NULL,
    status varchar(24) NOT NULL,
    reviewed_by varchar(255),
    review_note varchar(1000),
    requested_at timestamptz NOT NULL,
    reviewed_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_att_excuse_student
    ON attendance_excuse_requests(student_id);
CREATE INDEX IF NOT EXISTS idx_att_excuse_status
    ON attendance_excuse_requests(status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_att_excuse_pending
    ON attendance_excuse_requests(attendance_record_id)
    WHERE status = 'PENDING';

ALTER TABLE assignments ADD COLUMN IF NOT EXISTS updated_at timestamptz;
ALTER TABLE assignments ADD COLUMN IF NOT EXISTS last_reminder_at timestamptz;
ALTER TABLE assignments ADD COLUMN IF NOT EXISTS reminder_count integer DEFAULT 0;
ALTER TABLE assignment_submissions
    ADD COLUMN IF NOT EXISTS current_version integer DEFAULT 1;

CREATE TABLE IF NOT EXISTS assignment_submission_versions (
    id varchar(255) PRIMARY KEY,
    submission_id varchar(255) NOT NULL,
    version_no integer NOT NULL,
    content text,
    attachment_name varchar(255),
    attachment_file_id varchar(255),
    attachment_content_type varchar(255),
    attachment_size_bytes bigint,
    submitted_by varchar(255) NOT NULL,
    submitted_at timestamptz NOT NULL,
    CONSTRAINT uk_submission_version UNIQUE(submission_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_submission_version_submission
    ON assignment_submission_versions(submission_id);

CREATE TABLE IF NOT EXISTS submission_resubmission_requests (
    id varchar(255) PRIMARY KEY,
    submission_id varchar(255) NOT NULL,
    assignment_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    reason varchar(1000) NOT NULL,
    status varchar(24) NOT NULL,
    allowed_until timestamptz,
    requested_by varchar(255) NOT NULL,
    requested_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_resubmission_submission
    ON submission_resubmission_requests(submission_id);

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS deep_link varchar(255);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS group_key varchar(80);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS read_at timestamptz;

CREATE TABLE IF NOT EXISTS user_notification_preferences (
    id varchar(255) PRIMARY KEY,
    user_id varchar(255) NOT NULL,
    notification_type varchar(80) NOT NULL,
    channel varchar(24) NOT NULL,
    enabled boolean NOT NULL,
    updated_at timestamptz,
    CONSTRAINT uk_notification_preference
        UNIQUE(user_id, notification_type, channel)
);
CREATE INDEX IF NOT EXISTS idx_notification_preference_user
    ON user_notification_preferences(user_id);

CREATE TABLE IF NOT EXISTS year_result_publication_history (
    id varchar(255) PRIMARY KEY,
    publication_id varchar(255) NOT NULL,
    academic_year_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    publication_version integer NOT NULL,
    action varchar(32) NOT NULL,
    student_count integer NOT NULL,
    actor_id varchar(255) NOT NULL,
    reason text,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_year_result_history_class
    ON year_result_publication_history(
        academic_year_id, class_id, occurred_at DESC);
