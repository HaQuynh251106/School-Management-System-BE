CREATE TABLE IF NOT EXISTS homeroom_remarks (
    id VARCHAR(64) PRIMARY KEY,
    student_id VARCHAR(64) NOT NULL REFERENCES users(id),
    class_id VARCHAR(64) NOT NULL REFERENCES classes(id),
    academic_year_id VARCHAR(64) NOT NULL REFERENCES academic_years(id),
    semester_id VARCHAR(64) NOT NULL REFERENCES semesters(id),
    teacher_id VARCHAR(64) NOT NULL REFERENCES users(id),
    body VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_homeroom_remark_student_semester UNIQUE (student_id, semester_id),
    CONSTRAINT ck_homeroom_remark_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
);

CREATE INDEX IF NOT EXISTS idx_homeroom_remark_student
    ON homeroom_remarks(student_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_homeroom_remark_teacher
    ON homeroom_remarks(teacher_id, updated_at DESC);
