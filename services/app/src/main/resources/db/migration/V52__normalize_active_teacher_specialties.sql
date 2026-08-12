-- A teacher is schedulable only for the subject recorded as their primary
-- specialty. Historical capabilities are retained but disabled.

UPDATE public.teacher_subject_capabilities
SET active = false;

UPDATE public.teacher_subject_capabilities capability
   SET active = true
  FROM public.users teacher
 WHERE teacher.id = capability.teacher_id
   AND teacher.role = 'TEACHER'
   AND teacher.status = 'ACTIVE'
   AND capability.subject_id = CASE lower(trim(teacher.main_subject))
       WHEN 'toan' THEN 'sj-math'
       WHEN 'ngu van' THEN 'sj-lit'
       WHEN 'tieng anh' THEN 'sj-eng'
       WHEN 'tin hoc' THEN 'sj-it'
       WHEN 'vat ly' THEN 'sj-phys'
       WHEN 'hoa hoc' THEN 'sj-chem'
       WHEN 'sinh hoc' THEN 'sj-bio'
       WHEN 'lich su' THEN 'sj-hist'
       WHEN 'dia ly' THEN 'sj-geo'
       WHEN 'gdkt va pl' THEN 'sj-civic'
       WHEN 'giao duc the chat' THEN 'sj-pe'
       WHEN 'gdqp-an' THEN 'sj-def'
   END;

WITH active_qualified AS (
    SELECT capability.subject_id, capability.teacher_id,
           row_number() OVER (
               PARTITION BY capability.subject_id ORDER BY capability.teacher_id
           ) AS teacher_rank
    FROM public.teacher_subject_capabilities capability
    JOIN public.users teacher ON teacher.id = capability.teacher_id
    WHERE capability.active = true
      AND teacher.role = 'TEACHER'
      AND teacher.status = 'ACTIVE'
), qualified_counts AS (
    SELECT subject_id, count(*)::integer AS teacher_count
    FROM active_qualified
    GROUP BY subject_id
), ranked_assignments AS (
    SELECT assignment.id, assignment.subject_id, assignment.semester_id,
           row_number() OVER (
               PARTITION BY assignment.subject_id, assignment.semester_id
               ORDER BY assignment.class_code, assignment.id
           ) AS assignment_rank
    FROM public.teacher_class_subjects assignment
    WHERE assignment.status = 'ACTIVE'
), assignment_mapping AS (
    SELECT assignment.id, qualified.teacher_id
    FROM ranked_assignments assignment
    JOIN qualified_counts counts ON counts.subject_id = assignment.subject_id
    JOIN active_qualified qualified
      ON qualified.subject_id = assignment.subject_id
     AND qualified.teacher_rank = ((assignment.assignment_rank - 1) % counts.teacher_count) + 1
)
UPDATE public.teacher_class_subjects assignment
   SET teacher_id = mapping.teacher_id,
       teacher_name = teacher.full_name,
       updated_at = now()
  FROM assignment_mapping mapping
  JOIN public.users teacher ON teacher.id = mapping.teacher_id
 WHERE assignment.id = mapping.id;

DO $$
DECLARE
    invalid_assignment_count integer;
    active_teacher_count integer;
BEGIN
    SELECT count(*) INTO invalid_assignment_count
    FROM public.teacher_class_subjects assignment
    LEFT JOIN public.teacher_subject_capabilities capability
      ON capability.teacher_id = assignment.teacher_id
     AND capability.subject_id = assignment.subject_id
     AND capability.active = true
    JOIN public.users teacher ON teacher.id = assignment.teacher_id
    WHERE assignment.status = 'ACTIVE'
      AND (teacher.status <> 'ACTIVE' OR capability.id IS NULL);

    SELECT count(*) INTO active_teacher_count
    FROM public.users
    WHERE role = 'TEACHER' AND status = 'ACTIVE';

    IF invalid_assignment_count <> 0 THEN
        RAISE EXCEPTION 'Found % active assignments with an invalid teacher specialty',
            invalid_assignment_count;
    END IF;
    IF active_teacher_count <> 68 THEN
        RAISE EXCEPTION 'Expected exactly 68 active teachers, found %', active_teacher_count;
    END IF;
END $$;
