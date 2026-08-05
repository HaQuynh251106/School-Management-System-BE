-- Keep the denormalized user class in sync with the active academic year.
UPDATE users student
SET class_id = enrollment.class_id,
    class_name = class.code,
    updated_at = now()
FROM student_class_enrollments enrollment
JOIN academic_years year ON year.id = enrollment.academic_year_id
JOIN classes class ON class.id = enrollment.class_id
WHERE student.id = enrollment.student_id
  AND student.role = 'STUDENT'
  AND enrollment.status = 'ACTIVE'
  AND year.status = 'ACTIVE'
  AND (student.class_id IS DISTINCT FROM enrollment.class_id
       OR student.class_name IS DISTINCT FROM class.code);

-- Every class needs a distinct homeroom teacher for the fixed Saturday period.
WITH missing AS (
    SELECT class.id,
           row_number() OVER (ORDER BY class.grade_level, class.code) AS rn
    FROM classes class
    JOIN academic_years year ON year.id = class.academic_year_id
    WHERE year.status = 'ACTIVE'
      AND class.homeroom_teacher_id IS NULL
), candidates AS (
    SELECT teacher.id,
           row_number() OVER (ORDER BY teacher.id) AS rn
    FROM users teacher
    WHERE teacher.role = 'TEACHER'
      AND teacher.status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1 FROM classes assigned
          JOIN academic_years year ON year.id = assigned.academic_year_id
          WHERE year.status = 'ACTIVE'
            AND assigned.homeroom_teacher_id = teacher.id
      )
)
UPDATE classes class
SET homeroom_teacher_id = candidates.id
FROM missing
JOIN candidates ON candidates.rn = missing.rn
WHERE class.id = missing.id;

-- Fixed school activities are real timetable subjects, not UI-only labels.
INSERT INTO subjects (id, code, name, coefficient, required_room_type, active)
VALUES
    ('sj-flag', 'CHAOCO', 'Chao co', 1, 'GENERAL', true),
    ('sj-homeroom', 'SHL', 'Sinh hoat lop', 1, 'GENERAL', true)
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    active = true;

-- A1-A5 use a science-oriented three-subject block. Raise selected science
-- subjects to three weekly periods so a complete three-period block exists.
WITH selected(class_suffix, subject_id) AS (
    VALUES
        (1, 'sj-phys'), (1, 'sj-chem'), (1, 'sj-bio'),
        (2, 'sj-math'), (2, 'sj-phys'), (2, 'sj-chem'),
        (3, 'sj-eng'),  (3, 'sj-bio'),  (3, 'sj-chem'),
        (4, 'sj-math'), (4, 'sj-phys'), (4, 'sj-bio'),
        (5, 'sj-eng'),  (5, 'sj-chem'), (5, 'sj-bio')
)
UPDATE teacher_class_subjects assignment
SET weekly_periods = GREATEST(assignment.weekly_periods, 3),
    specialized_room_periods = CASE
        WHEN subject.required_room_type IN ('LAB', 'COMPUTER', 'GYM')
            THEN GREATEST(assignment.specialized_room_periods, 3)
        ELSE assignment.specialized_room_periods
    END,
    updated_at = now()
FROM classes class
JOIN academic_years year ON year.id = class.academic_year_id
JOIN selected ON selected.class_suffix =
    CAST(substring(class.code FROM 'A([0-9]+)$') AS integer)
JOIN subjects subject ON subject.id = selected.subject_id
WHERE assignment.class_id = class.id
  AND assignment.subject_id = selected.subject_id
  AND assignment.status = 'ACTIVE'
  AND year.status = 'ACTIVE';
