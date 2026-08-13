CREATE TABLE teacher_subject_qualifications (
    id VARCHAR(80) PRIMARY KEY,
    teacher_id VARCHAR(80) NOT NULL,
    subject_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_teacher_subject_qualification_teacher
        FOREIGN KEY (teacher_id) REFERENCES users(id),
    CONSTRAINT fk_teacher_subject_qualification_subject
        FOREIGN KEY (subject_id) REFERENCES subjects(id),
    CONSTRAINT uk_teacher_subject_qualification UNIQUE (teacher_id, subject_id)
);

CREATE INDEX idx_teacher_subject_qualification_teacher
    ON teacher_subject_qualifications (teacher_id);
