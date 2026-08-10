UPDATE public.teacher_staffing_policies
SET school_type = 'PUBLIC_REGULAR',
    weekly_teaching_norm = 17,
    teacher_class_ratio = 2.25,
    updated_at = now();

ALTER TABLE public.teacher_staffing_policies
    DROP CONSTRAINT IF EXISTS ck_teacher_staffing_school_type;

ALTER TABLE public.teacher_staffing_policies
    ADD CONSTRAINT ck_teacher_staffing_school_type
    CHECK (school_type = 'PUBLIC_REGULAR');
