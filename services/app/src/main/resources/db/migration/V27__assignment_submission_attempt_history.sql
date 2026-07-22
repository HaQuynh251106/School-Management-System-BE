CREATE TABLE assignment_submission_attempts (
    id varchar(255) PRIMARY KEY,
    submission_id varchar(255) NOT NULL,
    assignment_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    attempt_number integer NOT NULL,
    status varchar(255) NOT NULL,
    content varchar(4000),
    attachment_file_id varchar(255),
    attachment_name varchar(255),
    submitted_at timestamp(6) with time zone NOT NULL,
    score double precision,
    feedback varchar(2000),
    graded_by varchar(255),
    graded_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT uk_submission_attempt UNIQUE (submission_id, attempt_number)
);

CREATE INDEX idx_attempt_submission ON assignment_submission_attempts(submission_id, attempt_number);
CREATE INDEX idx_attempt_attachment ON assignment_submission_attempts(attachment_file_id);

INSERT INTO assignment_submission_attempts (
    id, submission_id, assignment_id, student_id, attempt_number, status, content,
    attachment_file_id, attachment_name, submitted_at, score, feedback, graded_by, graded_at, updated_at
)
SELECT CONCAT('attempt-', id, '-', attempt_number), id, assignment_id, student_id, attempt_number,
       status, content, attachment_file_id, attachment_name, submitted_at, score, feedback,
       graded_by, graded_at, COALESCE(graded_at, submitted_at, CURRENT_TIMESTAMP)
FROM assignment_submissions
WHERE submitted_at IS NOT NULL;
