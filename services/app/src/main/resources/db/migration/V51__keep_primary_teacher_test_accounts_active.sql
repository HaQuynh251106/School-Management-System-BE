-- The primary teacher accounts are part of the documented local test flow.
-- Keep them active while preserving the 68-teacher workforce established in V50.

UPDATE public.users
   SET status = 'ACTIVE',
       updated_at = now()
 WHERE id IN (
     'u-t-math', 'u-t-lit', 'u-t-eng', 'u-t-phys',
     'u-t-chem', 'u-t-bio', 'u-t-hist', 'u-t-geo',
     'u-t-it', 'u-t-pe', 'u-t-civic', 'u-t-defense'
 );

UPDATE public.teacher_subject_capabilities
   SET active = true
 WHERE teacher_id IN (
     'u-t-math', 'u-t-lit', 'u-t-eng', 'u-t-phys',
     'u-t-chem', 'u-t-bio', 'u-t-hist', 'u-t-geo',
     'u-t-it', 'u-t-pe', 'u-t-civic', 'u-t-defense'
 );

CREATE TEMP TABLE v51_teachers_to_lock ON COMMIT DROP AS
WITH subject_targets(subject_key, target_count) AS (
    VALUES
        ('MATH', 7), ('LIT', 8), ('ENG', 7), ('IT', 6),
        ('PHYS', 5), ('CHEM', 5), ('BIO', 5),
        ('HIST', 5), ('GEO', 5), ('CIVIC', 5),
        ('PE', 5), ('DEF', 5)
), candidates AS (
    SELECT u.id,
           CASE lower(trim(u.main_subject))
             WHEN 'toan' THEN 'MATH'
             WHEN 'ngu van' THEN 'LIT'
             WHEN 'tieng anh' THEN 'ENG'
             WHEN 'tin hoc' THEN 'IT'
             WHEN 'vat ly' THEN 'PHYS'
             WHEN 'hoa hoc' THEN 'CHEM'
             WHEN 'sinh hoc' THEN 'BIO'
             WHEN 'lich su' THEN 'HIST'
             WHEN 'dia ly' THEN 'GEO'
             WHEN 'gdkt va pl' THEN 'CIVIC'
             WHEN 'giao duc the chat' THEN 'PE'
             WHEN 'gdqp-an' THEN 'DEF'
           END AS subject_key,
           u.id LIKE 'u-t-%' AS is_primary_account,
           EXISTS (
               SELECT 1
               FROM public.classes class
               JOIN public.academic_years year ON year.id = class.academic_year_id
               WHERE class.homeroom_teacher_id = u.id
                 AND class.status = 'ACTIVE'
                 AND year.status = 'ACTIVE'
           ) AS is_homeroom
    FROM public.users u
    WHERE u.role = 'TEACHER' AND u.status = 'ACTIVE'
), ranked AS (
    SELECT candidate.id, candidate.subject_key, target.target_count,
           row_number() OVER (
               PARTITION BY candidate.subject_key
               ORDER BY candidate.is_primary_account DESC,
                        candidate.is_homeroom DESC,
                        candidate.id
           ) AS teacher_rank
    FROM candidates candidate
    JOIN subject_targets target ON target.subject_key = candidate.subject_key
)
SELECT id, subject_key
FROM ranked
WHERE teacher_rank > target_count;

WITH primary_accounts(subject_key, teacher_id) AS (
    VALUES
        ('MATH', 'u-t-math'), ('LIT', 'u-t-lit'), ('ENG', 'u-t-eng'),
        ('IT', 'u-t-it'), ('PHYS', 'u-t-phys'), ('CHEM', 'u-t-chem'),
        ('BIO', 'u-t-bio'), ('HIST', 'u-t-hist'), ('GEO', 'u-t-geo'),
        ('CIVIC', 'u-t-civic'), ('PE', 'u-t-pe'), ('DEF', 'u-t-defense')
)
UPDATE public.classes class
   SET homeroom_teacher_id = primary_account.teacher_id
  FROM v51_teachers_to_lock locked_teacher,
       primary_accounts primary_account,
       public.academic_years year
 WHERE class.homeroom_teacher_id = locked_teacher.id
   AND primary_account.subject_key = locked_teacher.subject_key
   AND year.id = class.academic_year_id
   AND class.status = 'ACTIVE'
   AND year.status = 'ACTIVE';

UPDATE public.users teacher
   SET status = 'LOCKED',
       session_version = COALESCE(teacher.session_version, 0) + 1,
       updated_at = now()
 WHERE teacher.id IN (SELECT id FROM v51_teachers_to_lock);

UPDATE public.refresh_tokens token
   SET revoked_at = COALESCE(token.revoked_at, now())
 WHERE token.user_id IN (SELECT id FROM v51_teachers_to_lock);

UPDATE public.user_devices device
   SET active = false
 WHERE device.user_id IN (
     SELECT id FROM public.users
     WHERE role = 'TEACHER' AND status = 'LOCKED'
 );

UPDATE public.teacher_subject_capabilities capability
   SET active = (teacher.status = 'ACTIVE')
  FROM public.users teacher
 WHERE teacher.id = capability.teacher_id
   AND teacher.role = 'TEACHER';

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
    active_teacher_count integer;
    active_primary_count integer;
    active_homeroom_count integer;
    is_full_school_dataset boolean;
BEGIN
    SELECT count(*) INTO active_teacher_count
    FROM public.users
    WHERE role = 'TEACHER' AND status = 'ACTIVE';

    SELECT count(*) INTO active_primary_count
    FROM public.users
    WHERE role = 'TEACHER' AND status = 'ACTIVE' AND id LIKE 'u-t-%';

    SELECT count(DISTINCT class.homeroom_teacher_id) INTO active_homeroom_count
    FROM public.classes class
    JOIN public.academic_years year ON year.id = class.academic_year_id
    JOIN public.users teacher ON teacher.id = class.homeroom_teacher_id
    WHERE class.status = 'ACTIVE'
      AND year.status = 'ACTIVE'
      AND teacher.status = 'ACTIVE';

    SELECT count(*) >= 30 INTO is_full_school_dataset
    FROM public.classes class
    JOIN public.academic_years year ON year.id = class.academic_year_id
    WHERE class.status = 'ACTIVE' AND year.status = 'ACTIVE';

    IF is_full_school_dataset AND active_teacher_count <> 68 THEN
        RAISE EXCEPTION 'Expected exactly 68 active teachers, found %', active_teacher_count;
    END IF;
    IF is_full_school_dataset AND active_primary_count <> 12 THEN
        RAISE EXCEPTION 'Expected all 12 primary teacher accounts active, found %', active_primary_count;
    END IF;
    IF is_full_school_dataset AND active_homeroom_count < 30 THEN
        RAISE EXCEPTION 'Expected 30 active homeroom teachers, found %', active_homeroom_count;
    END IF;
END $$;
