-- A teacher can lead only one class in the active academic year because all
-- homeroom periods occur at the same fixed Saturday slot.
WITH ranked_duplicates AS (
    SELECT class.id,
           row_number() OVER (
               PARTITION BY class.homeroom_teacher_id
               ORDER BY class.grade_level, class.code, class.id
           ) AS duplicate_rank
    FROM classes class
    JOIN academic_years year ON year.id = class.academic_year_id
    WHERE year.status = 'ACTIVE'
      AND class.homeroom_teacher_id IS NOT NULL
), classes_to_reassign AS (
    SELECT id,
           row_number() OVER (ORDER BY id) AS rn
    FROM ranked_duplicates
    WHERE duplicate_rank > 1
), available_teachers AS (
    SELECT teacher.id,
           row_number() OVER (ORDER BY teacher.id) AS rn
    FROM users teacher
    WHERE teacher.role = 'TEACHER'
      AND teacher.status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1
          FROM classes assigned
          JOIN academic_years year ON year.id = assigned.academic_year_id
          WHERE year.status = 'ACTIVE'
            AND assigned.homeroom_teacher_id = teacher.id
      )
)
UPDATE classes class
SET homeroom_teacher_id = teacher.id
FROM classes_to_reassign duplicate
JOIN available_teachers teacher ON teacher.rn = duplicate.rn
WHERE class.id = duplicate.id;
