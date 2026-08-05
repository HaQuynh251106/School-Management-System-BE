-- Keep staged whole-school scheduling independent between grades. Each active
-- class receives a distinct homeroom teacher who already teaches that grade.
WITH active_semester AS (
    SELECT semester.id
    FROM semesters semester
    JOIN academic_years year ON year.id = semester.academic_year_id
    WHERE year.status = 'ACTIVE'
    ORDER BY semester.sequence
    LIMIT 1
), ranked_classes AS (
    SELECT class.id,
           class.grade_level,
           row_number() OVER (
               PARTITION BY class.grade_level
               ORDER BY CAST(substring(class.code FROM 'A([0-9]+)$') AS integer), class.id
           ) AS rn
    FROM classes class
    JOIN academic_years year ON year.id = class.academic_year_id
    WHERE year.status = 'ACTIVE'
), grade_teachers AS (
    SELECT grade_level,
           teacher_id,
           row_number() OVER (
               PARTITION BY grade_level
               ORDER BY teacher_id
           ) AS rn
    FROM (
        SELECT DISTINCT class.grade_level, assignment.teacher_id
        FROM teacher_class_subjects assignment
        JOIN classes class ON class.id = assignment.class_id
        JOIN active_semester semester ON semester.id = assignment.semester_id
        WHERE assignment.status = 'ACTIVE'
    ) available
)
UPDATE classes class
SET homeroom_teacher_id = teacher.teacher_id
FROM ranked_classes target
JOIN grade_teachers teacher
  ON teacher.grade_level = target.grade_level
 AND teacher.rn = target.rn
WHERE class.id = target.id;
