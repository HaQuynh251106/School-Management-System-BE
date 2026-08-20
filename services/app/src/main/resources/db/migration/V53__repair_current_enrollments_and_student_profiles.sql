ALTER TABLE public.users ADD COLUMN IF NOT EXISTS date_of_birth date;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS gender varchar(16);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS place_of_birth varchar(255);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS ethnicity varchar(100);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS nationality varchar(100);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS address varchar(1000);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS enrollment_date date;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS guardian_name varchar(255);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS guardian_phone varchar(32);

-- Reconcile old accounts that had users.class_id but were not represented in the
-- canonical enrollment history. First preserve the actual roster size as a valid
-- class limit, then upsert one active enrollment per student/year.
UPDATE public.classes class
SET max_students = GREATEST(
        COALESCE(class.max_students, 1),
        (SELECT count(*)::integer FROM public.users student
         WHERE student.role = 'STUDENT'
           AND student.status <> 'DELETED'
           AND student.class_id = class.id)
    )
WHERE EXISTS (
    SELECT 1 FROM public.academic_years year
    WHERE year.id = class.academic_year_id AND year.status = 'ACTIVE'
);

UPDATE public.rooms room
SET capacity = GREATEST(COALESCE(room.capacity, 1), class.max_students)
FROM public.classes class
WHERE class.home_room_id = room.id;

INSERT INTO public.student_class_enrollments (
    id, academic_year_id, class_id, student_id, student_code, student_name,
    enrollment_type, status, enrolled_by, enrolled_at
)
SELECT
    'enr-v53-' || student.id,
    class.academic_year_id,
    class.id,
    student.id,
    student.student_code,
    student.full_name,
    'RECONCILED',
    'ACTIVE',
    'SYSTEM',
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
    enrollment_type = 'RECONCILED',
    status = 'ACTIVE';

UPDATE public.classes class
SET student_count = COALESCE((
    SELECT count(*)::integer
    FROM public.student_class_enrollments enrollment
    WHERE enrollment.academic_year_id = class.academic_year_id
      AND enrollment.class_id = class.id
      AND enrollment.status = 'ACTIVE'
), 0);
