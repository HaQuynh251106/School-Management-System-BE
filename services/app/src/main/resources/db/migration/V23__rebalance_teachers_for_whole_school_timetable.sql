-- Use the complete active teaching staff instead of forcing one teacher to
-- carry every class of a grade. Published timetables remain snapshots; new
-- drafts use these balanced assignments.
WITH ranked AS (
    SELECT assignment.id, assignment.subject_id, class.grade_level,
           row_number() OVER (
               PARTITION BY assignment.semester_id, assignment.subject_id, class.grade_level
               ORDER BY class.code, assignment.id) AS rn
    FROM teacher_class_subjects assignment
    JOIN classes class ON class.id = assignment.class_id
    WHERE assignment.status = 'ACTIVE'
      AND assignment.subject_id IN (
        'sj-phys','sj-chem','sj-bio','sj-hist','sj-geo',
        'sj-it','sj-pe','sj-civic','sj-def')
), mapped AS (
    SELECT ranked.id,
           CASE ranked.subject_id
             WHEN 'sj-phys' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-phys','g0-teacher-phys-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-phys-2','g0-teacher-phys-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-phys-3','g0-teacher-phys-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-chem' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-chem','g0-teacher-chem-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-chem-2','g0-teacher-chem-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-chem-3','g0-teacher-chem-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-bio' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-bio','g0-teacher-bio-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-bio-2','g0-teacher-bio-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-bio-3','g0-teacher-bio-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-hist' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-hist','g0-teacher-hist-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-hist-2','g0-teacher-hist-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-hist-3','g0-teacher-hist-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-geo' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-geo','g0-teacher-geo-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-geo-2','g0-teacher-geo-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-geo-3','g0-teacher-geo-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-it' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-it','g0-teacher-it-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-it-2','g0-teacher-it-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-it-3','g0-teacher-it-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-pe' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-pe','g0-teacher-pe-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-pe-2','g0-teacher-pe-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-pe-3','g0-teacher-pe-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-civic' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-civic','g0-teacher-civic-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-civic-2','g0-teacher-civic-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-civic-3','g0-teacher-civic-6'])[((rn - 1) % 2) + 1] END
             WHEN 'sj-def' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-defense','g0-teacher-def-4'])[((rn - 1) % 2) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-def-2','g0-teacher-def-5'])[((rn - 1) % 2) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-def-3','g0-teacher-def-6'])[((rn - 1) % 2) + 1] END
           END AS teacher_id
    FROM ranked
)
UPDATE teacher_class_subjects assignment
SET teacher_id = mapped.teacher_id,
    teacher_name = teacher.full_name,
    updated_at = now()
FROM mapped
JOIN users teacher ON teacher.id = mapped.teacher_id
WHERE assignment.id = mapped.id;

WITH ranked AS (
    SELECT assignment.id, assignment.subject_id, class.grade_level,
           row_number() OVER (
               PARTITION BY assignment.semester_id, assignment.subject_id, class.grade_level
               ORDER BY class.code, assignment.id) AS rn
    FROM teacher_class_subjects assignment
    JOIN classes class ON class.id = assignment.class_id
    WHERE assignment.status = 'ACTIVE'
      AND assignment.subject_id IN ('sj-math','sj-lit','sj-eng')
), mapped AS (
    SELECT ranked.id,
           CASE ranked.subject_id
             WHEN 'sj-math' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-math','g0-teacher-math-4','g0-teacher-math-5'])[((rn - 1) % 3) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-math-2','g0-teacher-math-6','g0-teacher-math-7'])[((rn - 1) % 3) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-math-3','g0-teacher-math-8','g0-teacher-math-9'])[((rn - 1) % 3) + 1] END
             WHEN 'sj-lit' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-lit','g0-teacher-lit-4','g0-teacher-lit-5'])[((rn - 1) % 3) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-lit-2','g0-teacher-lit-6','g0-teacher-lit-7'])[((rn - 1) % 3) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-lit-3','g0-teacher-lit-8','g0-teacher-lit-9'])[((rn - 1) % 3) + 1] END
             WHEN 'sj-eng' THEN CASE ranked.grade_level
               WHEN 'K10' THEN (ARRAY['u-t-eng','g0-teacher-eng-4','g0-teacher-eng-5'])[((rn - 1) % 3) + 1]
               WHEN 'K11' THEN (ARRAY['g0-teacher-eng-2','g0-teacher-eng-6','g0-teacher-eng-7'])[((rn - 1) % 3) + 1]
               WHEN 'K12' THEN (ARRAY['g0-teacher-eng-3','g0-teacher-eng-8','g0-teacher-eng-9'])[((rn - 1) % 3) + 1] END
           END AS teacher_id
    FROM ranked
)
UPDATE teacher_class_subjects assignment
SET teacher_id = mapped.teacher_id,
    teacher_name = teacher.full_name,
    updated_at = now()
FROM mapped
JOIN users teacher ON teacher.id = mapped.teacher_id
WHERE assignment.id = mapped.id;
