CREATE TABLE education_plans (
    id VARCHAR(80) PRIMARY KEY,
    academic_year_id VARCHAR(80) NOT NULL,
    grade_level VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    description VARCHAR(2000),
    source_plan_id VARCHAR(80),
    revision_reason VARCHAR(1000),
    created_by VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_by VARCHAR(80),
    submitted_at TIMESTAMP WITH TIME ZONE,
    approved_by VARCHAR(80),
    approved_at TIMESTAMP WITH TIME ZONE,
    published_by VARCHAR(80),
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_education_plan_version UNIQUE (academic_year_id, grade_level, version_no),
    CONSTRAINT ck_education_plan_status CHECK
        (status IN ('DRAFT','SUBMITTED','REVISION_REQUESTED','APPROVED','PUBLISHED','SUPERSEDED','LOCKED'))
);

CREATE INDEX idx_education_plan_scope
    ON education_plans (academic_year_id, grade_level, status, version_no);

ALTER TABLE curriculum_requirements ADD COLUMN plan_id VARCHAR(80);

INSERT INTO education_plans
    (id, academic_year_id, grade_level, name, version_no, status,
     description, created_by, created_at, updated_at, published_by, published_at)
SELECT 'ep-legacy-' || s.academic_year_id || '-' || LOWER(cr.grade_level),
       s.academic_year_id,
       cr.grade_level,
       'Kế hoạch giáo dục đã chuyển đổi - ' || cr.grade_level,
       1,
       'PUBLISHED',
       'Dữ liệu kế hoạch được chuyển đổi từ phiên bản trước khi bổ sung quy trình GĐ3.',
       'SYSTEM',
       MIN(cr.created_at),
       MAX(cr.updated_at),
       'SYSTEM',
       MAX(cr.updated_at)
FROM curriculum_requirements cr
JOIN semesters s ON s.id = cr.semester_id
GROUP BY s.academic_year_id, cr.grade_level;

UPDATE curriculum_requirements cr
SET plan_id = (
    SELECT ep.id
    FROM education_plans ep
    JOIN semesters s ON s.academic_year_id = ep.academic_year_id
    WHERE s.id = cr.semester_id AND ep.grade_level = cr.grade_level
      AND ep.status = 'PUBLISHED'
);

ALTER TABLE curriculum_requirements DROP CONSTRAINT IF EXISTS uk_curriculum_requirement;
ALTER TABLE curriculum_requirements ALTER COLUMN plan_id SET NOT NULL;
ALTER TABLE curriculum_requirements
    ADD CONSTRAINT uk_curriculum_requirement_plan
        UNIQUE (plan_id, semester_id, subject_id);
ALTER TABLE curriculum_requirements
    ADD CONSTRAINT fk_curriculum_requirement_plan
        FOREIGN KEY (plan_id) REFERENCES education_plans(id) ON DELETE RESTRICT;

ALTER TABLE timetable_plans ADD COLUMN source_education_plan_ids TEXT;

