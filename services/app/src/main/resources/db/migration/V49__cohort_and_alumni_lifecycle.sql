ALTER TABLE cohorts ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE users ADD COLUMN IF NOT EXISTS student_status VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS graduated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS graduation_academic_year_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS graduation_class_id VARCHAR(255);

UPDATE users
SET student_status = CASE
    WHEN EXISTS (
        SELECT 1 FROM student_yearly_summaries summary
        WHERE summary.student_id = users.id
          AND summary.promotion_status = 'GRADUATED'
          AND summary.finalized_at IS NOT NULL
    ) THEN 'GRADUATED'
    ELSE 'ENROLLED'
END
WHERE role = 'STUDENT' AND student_status IS NULL;

INSERT INTO cohorts(id, code, name, entry_year, graduation_year, duration_years, status,
                    entry_academic_year_id, created_at, created_by)
SELECT 'cohort-' || CAST(source.entry_year AS VARCHAR) || '-' || CAST(source.entry_year + 3 AS VARCHAR),
       CAST(source.entry_year AS VARCHAR) || '-' || CAST(source.entry_year + 3 AS VARCHAR),
       'Niên khóa ' || CAST(source.entry_year AS VARCHAR) || '-' || CAST(source.entry_year + 3 AS VARCHAR),
       source.entry_year, source.entry_year + 3, 3, 'ACTIVE', source.entry_academic_year_id,
       CURRENT_TIMESTAMP, 'migration-v49'
FROM (
    SELECT DISTINCT
           CAST(EXTRACT(YEAR FROM year_row.start_date) AS INTEGER)
             - CASE
                 WHEN UPPER(class_row.grade_level) IN ('K12', '12') THEN 2
                 WHEN UPPER(class_row.grade_level) IN ('K11', '11') THEN 1
                 ELSE 0
               END AS entry_year,
           CASE
             WHEN UPPER(class_row.grade_level) IN ('K10', '10') THEN year_row.id
             ELSE NULL
           END AS entry_academic_year_id
    FROM classes class_row
    JOIN academic_years year_row ON year_row.id = class_row.academic_year_id
    WHERE UPPER(class_row.grade_level) IN ('K10', '10', 'K11', '11', 'K12', '12')
) source
WHERE NOT EXISTS (
    SELECT 1 FROM cohorts existing
    WHERE existing.code = CAST(source.entry_year AS VARCHAR) || '-' || CAST(source.entry_year + 3 AS VARCHAR)
);

UPDATE classes
SET cohort_id = (
    SELECT cohort_row.id
    FROM cohorts cohort_row
    JOIN academic_years year_row ON year_row.id = classes.academic_year_id
    WHERE cohort_row.entry_year = CAST(EXTRACT(YEAR FROM year_row.start_date) AS INTEGER)
      - CASE
          WHEN UPPER(classes.grade_level) IN ('K12', '12') THEN 2
          WHEN UPPER(classes.grade_level) IN ('K11', '11') THEN 1
          ELSE 0
        END
    FETCH FIRST 1 ROW ONLY
)
WHERE cohort_id IS NULL
  AND UPPER(grade_level) IN ('K10', '10', 'K11', '11', 'K12', '12');

UPDATE users
SET cohort_id = (SELECT class_row.cohort_id FROM classes class_row WHERE class_row.id = users.class_id)
WHERE role = 'STUDENT' AND cohort_id IS NULL AND class_id IS NOT NULL;

UPDATE class_enrollments
SET cohort_id = (SELECT class_row.cohort_id FROM classes class_row WHERE class_row.id = class_enrollments.class_id)
WHERE cohort_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_student_status ON users(student_status);
CREATE INDEX IF NOT EXISTS idx_users_graduation_year ON users(graduation_academic_year_id);
CREATE INDEX IF NOT EXISTS idx_users_graduated_at ON users(graduated_at);
