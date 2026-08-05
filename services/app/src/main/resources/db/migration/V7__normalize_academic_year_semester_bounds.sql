WITH semester_bounds AS (
    SELECT
        academic_year_id,
        min(start_date) AS first_semester_date,
        max(end_date) AS last_semester_date
    FROM semesters
    GROUP BY academic_year_id
)
UPDATE academic_years ay
SET
    start_date = bounds.first_semester_date,
    end_date = bounds.last_semester_date
FROM semester_bounds bounds
WHERE bounds.academic_year_id = ay.id
  AND (
      ay.start_date IS DISTINCT FROM bounds.first_semester_date
      OR ay.end_date IS DISTINCT FROM bounds.last_semester_date
  );
