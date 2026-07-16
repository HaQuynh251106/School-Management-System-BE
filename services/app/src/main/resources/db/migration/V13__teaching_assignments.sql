CREATE TABLE teaching_assignments (
    weekly_periods integer NOT NULL,
    assigned_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    assigned_by varchar(255),
    class_code varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    id varchar(255) NOT NULL,
    semester_id varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    subject_name varchar(255) NOT NULL,
    teacher_id varchar(255) NOT NULL,
    teacher_name varchar(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_teaching_assignment_scope
    ON teaching_assignments (class_id, subject_id, semester_id);
CREATE INDEX idx_ta_teacher_semester
    ON teaching_assignments (teacher_id, semester_id);
CREATE INDEX idx_ta_class_semester
    ON teaching_assignments (class_id, semester_id);

INSERT INTO teaching_assignments (
    id, class_id, class_code, subject_id, subject_name, teacher_id, teacher_name,
    semester_id, weekly_periods, assigned_at, assigned_by, updated_at
)
SELECT CONCAT('ta-', t.class_id, '-', t.subject_id, '-', t.semester_id),
       t.class_id, c.code, t.subject_id, MAX(t.subject_name), MAX(t.teacher_id),
       MAX(t.teacher_name), t.semester_id, CAST(COUNT(*) AS integer),
       CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM timetable_slots t
JOIN classes c ON c.id = t.class_id
WHERE t.class_id IS NOT NULL
  AND t.subject_id IS NOT NULL
  AND t.teacher_id IS NOT NULL
  AND t.semester_id IS NOT NULL
GROUP BY t.class_id, c.code, t.subject_id, t.semester_id;
