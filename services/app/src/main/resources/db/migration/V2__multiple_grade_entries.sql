ALTER TABLE exam_categories
    ADD COLUMN required_count integer NOT NULL DEFAULT 1;

ALTER TABLE grades
    ADD COLUMN assessment_index integer NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX uq_grade_assessment
    ON grades (student_id, subject_id, semester_id, category, assessment_index);
