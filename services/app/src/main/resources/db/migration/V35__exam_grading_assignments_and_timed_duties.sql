CREATE TABLE exam_grading_assignments (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL,
    schedule_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    class_code varchar(100) NOT NULL,
    subject_id varchar(255) NOT NULL,
    subject_name varchar(255) NOT NULL,
    teacher_id varchar(255) NOT NULL,
    teacher_name varchar(255) NOT NULL,
    assigned_at timestamp with time zone NOT NULL,
    assigned_by varchar(255),
    CONSTRAINT fk_exam_grader_period FOREIGN KEY (exam_period_id)
        REFERENCES exam_periods(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_grader_schedule FOREIGN KEY (schedule_id)
        REFERENCES exam_schedules(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_grader_class FOREIGN KEY (class_id)
        REFERENCES classes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_grader_teacher FOREIGN KEY (teacher_id)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT uk_exam_grader_schedule_class UNIQUE (schedule_id, class_id)
);

CREATE INDEX idx_exam_grader_teacher
    ON exam_grading_assignments(teacher_id, schedule_id);
CREATE INDEX idx_exam_grader_period
    ON exam_grading_assignments(exam_period_id, schedule_id);

-- Preserve the former behavior for existing data by converting the subject
-- teacher of each scheduled class into an explicit grading assignment.
INSERT INTO exam_grading_assignments (
    id, exam_period_id, schedule_id, class_id, class_code, subject_id,
    subject_name, teacher_id, teacher_name, assigned_at, assigned_by
)
SELECT
    CONCAT('ega-', es.id, '-', esc.class_id),
    es.exam_period_id,
    es.id,
    esc.class_id,
    ta.class_code,
    es.subject_id,
    es.subject_name,
    ta.teacher_id,
    ta.teacher_name,
    CURRENT_TIMESTAMP,
    'SYSTEM_MIGRATION'
FROM exam_schedules es
JOIN exam_periods ep ON ep.id = es.exam_period_id
JOIN exam_schedule_classes esc ON esc.schedule_id = es.id
JOIN teaching_assignments ta
  ON ta.class_id = esc.class_id
 AND ta.subject_id = es.subject_id
 AND ta.semester_id = ep.semester_id;
