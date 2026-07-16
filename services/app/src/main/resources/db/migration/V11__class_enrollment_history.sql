CREATE TABLE class_enrollments (
    enrolled_at timestamp(6) with time zone NOT NULL,
    ended_at timestamp(6) with time zone,
    academic_year_id varchar(255),
    class_id varchar(255) NOT NULL,
    id varchar(255) NOT NULL,
    status varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (academic_year_id, class_id, student_id)
);
CREATE INDEX idx_enrollment_student ON class_enrollments (student_id, status);
CREATE INDEX idx_enrollment_class ON class_enrollments (class_id, status);
