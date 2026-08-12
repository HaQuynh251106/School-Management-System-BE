-- Keep the regular public-school workforce at the minimum practical level for
-- 30 classes: ceil(30 * 2.25) = 68 active teachers. Homeroom teachers always
-- have priority. Remaining teaching assignments are balanced among active
-- teachers of the same subject so no class loses its assigned specialist.

WITH subject_targets(subject_key, target_count) AS (
    VALUES
        ('MATH', 7), ('LIT', 8), ('ENG', 7), ('IT', 6),
        ('PHYS', 5), ('CHEM', 5), ('BIO', 5),
        ('HIST', 5), ('GEO', 5), ('CIVIC', 5),
        ('PE', 5), ('DEF', 5)
), teacher_candidates AS (
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
           EXISTS (
               SELECT 1
               FROM public.classes c
               JOIN public.academic_years year ON year.id = c.academic_year_id
               WHERE c.homeroom_teacher_id = u.id
                 AND c.status = 'ACTIVE'
                 AND year.status = 'ACTIVE'
           ) AS is_homeroom
    FROM public.users u
    WHERE u.role = 'TEACHER' AND u.status = 'ACTIVE'
), ranked AS (
    SELECT candidate.id, candidate.subject_key, target.target_count,
           row_number() OVER (
               PARTITION BY candidate.subject_key
               ORDER BY candidate.is_homeroom DESC, candidate.id
           ) AS teacher_rank
    FROM teacher_candidates candidate
    LEFT JOIN subject_targets target ON target.subject_key = candidate.subject_key
), locked AS (
    UPDATE public.users u
       SET status = 'LOCKED',
           session_version = COALESCE(u.session_version, 0) + 1,
           updated_at = now()
      FROM ranked r
     WHERE u.id = r.id
       AND (r.subject_key IS NULL OR r.teacher_rank > r.target_count)
    RETURNING u.id
)
UPDATE public.refresh_tokens token
   SET revoked_at = COALESCE(token.revoked_at, now())
 WHERE token.user_id IN (SELECT id FROM locked);

UPDATE public.user_devices device
   SET active = false
 WHERE device.user_id IN (
     SELECT id FROM public.users
     WHERE role = 'TEACHER' AND status = 'LOCKED'
 );

UPDATE public.teacher_subject_capabilities capability
   SET active = false
 WHERE capability.teacher_id IN (
     SELECT id FROM public.users
     WHERE role = 'TEACHER' AND status = 'LOCKED'
 );

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
    active_homeroom_count integer;
BEGIN
    SELECT count(*) INTO active_teacher_count
    FROM public.users
    WHERE role = 'TEACHER' AND status = 'ACTIVE';

    SELECT count(DISTINCT c.homeroom_teacher_id) INTO active_homeroom_count
    FROM public.classes c
    JOIN public.academic_years year ON year.id = c.academic_year_id
    JOIN public.users teacher ON teacher.id = c.homeroom_teacher_id
    WHERE c.status = 'ACTIVE'
      AND year.status = 'ACTIVE'
      AND teacher.status = 'ACTIVE';

    IF active_teacher_count <> 68 THEN
        RAISE EXCEPTION 'Expected exactly 68 active teachers, found %', active_teacher_count;
    END IF;
    IF active_homeroom_count < 30 THEN
        RAISE EXCEPTION 'Expected 30 active homeroom teachers, found %', active_homeroom_count;
    END IF;
END $$;
