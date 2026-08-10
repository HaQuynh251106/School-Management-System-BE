CREATE TABLE public.teacher_staffing_policies (
    id varchar(80) PRIMARY KEY,
    academic_year_id varchar(80) NOT NULL
        REFERENCES public.academic_years(id) ON DELETE CASCADE,
    school_type varchar(32) NOT NULL DEFAULT 'PUBLIC_REGULAR',
    weekly_teaching_norm integer NOT NULL DEFAULT 17,
    teaching_weeks integer NOT NULL DEFAULT 35,
    teacher_class_ratio numeric(4,2) NOT NULL DEFAULT 2.25,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_teacher_staffing_policy_year UNIQUE (academic_year_id),
    CONSTRAINT ck_teacher_staffing_school_type CHECK (
        school_type IN ('PUBLIC_REGULAR', 'ETHNIC_BOARDING', 'SPECIALIZED')
    ),
    CONSTRAINT ck_teacher_staffing_weekly_norm CHECK (
        weekly_teaching_norm BETWEEN 1 AND 30
    ),
    CONSTRAINT ck_teacher_staffing_weeks CHECK (
        teaching_weeks BETWEEN 1 AND 52
    ),
    CONSTRAINT ck_teacher_staffing_ratio CHECK (
        teacher_class_ratio > 0 AND teacher_class_ratio <= 5
    )
);

INSERT INTO public.teacher_staffing_policies (
    id, academic_year_id, school_type, weekly_teaching_norm,
    teaching_weeks, teacher_class_ratio
)
SELECT 'staffing-' || ay.id, ay.id, 'PUBLIC_REGULAR', 17, 35, 2.25
FROM public.academic_years ay
ON CONFLICT (academic_year_id) DO NOTHING;

