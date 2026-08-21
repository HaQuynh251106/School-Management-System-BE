-- Excel imports created after V53 populated users.class_id but did not create the
-- canonical enrollment row. Reconcile those students before audience-specific
-- academic reads resolve their current class.
INSERT INTO public.student_class_enrollments (
    id, academic_year_id, class_id, student_id, student_code, student_name,
    enrollment_type, status, enrolled_by, enrolled_at
)
SELECT
    'enr-v57-' || student.id,
    class.academic_year_id,
    class.id,
    student.id,
    student.student_code,
    student.full_name,
    'IMPORT_RECONCILED',
    'ACTIVE',
    NULL,
    now()
FROM public.users student
JOIN public.classes class ON class.id = student.class_id
JOIN public.academic_years year ON year.id = class.academic_year_id
WHERE student.role = 'STUDENT'
  AND student.status <> 'DELETED'
  AND year.status = 'ACTIVE'
ON CONFLICT (academic_year_id, student_id) DO UPDATE
SET class_id = excluded.class_id,
    student_code = excluded.student_code,
    student_name = excluded.student_name,
    enrollment_type = 'IMPORT_RECONCILED',
    status = 'ACTIVE',
    reverted_by = NULL,
    reverted_at = NULL;

UPDATE public.classes class
SET student_count = COALESCE((
    SELECT count(*)::integer
    FROM public.student_class_enrollments enrollment
    WHERE enrollment.academic_year_id = class.academic_year_id
      AND enrollment.class_id = class.id
      AND enrollment.status = 'ACTIVE'
), 0)
WHERE EXISTS (
    SELECT 1
    FROM public.academic_years year
    WHERE year.id = class.academic_year_id
      AND year.status = 'ACTIVE'
);
