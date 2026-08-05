ALTER TABLE public.exam_schedule_versions
    ADD COLUMN IF NOT EXISTS content_updated_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_validated_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_validation_error_count integer,
    ADD COLUMN IF NOT EXISTS last_validation_warning_count integer;

UPDATE public.exam_schedule_versions
SET content_updated_at = COALESCE(content_updated_at, created_at)
WHERE content_updated_at IS NULL;

ALTER TABLE public.exam_sessions
    ADD COLUMN IF NOT EXISTS source_plan_name varchar(255),
    ADD COLUMN IF NOT EXISTS source_plan_status varchar(32),
    ADD COLUMN IF NOT EXISTS source_assessment_name varchar(255),
    ADD COLUMN IF NOT EXISTS source_assessment_type varchar(40),
    ADD COLUMN IF NOT EXISTS source_assessment_form varchar(80),
    ADD COLUMN IF NOT EXISTS source_assessment_week integer,
    ADD COLUMN IF NOT EXISTS source_planned_start_date date,
    ADD COLUMN IF NOT EXISTS source_planned_end_date date,
    ADD COLUMN IF NOT EXISTS source_synced_at timestamptz,
    ADD COLUMN IF NOT EXISTS source_updated_at timestamptz,
    ADD COLUMN IF NOT EXISTS schedule_deviation_reason varchar(1000);

UPDATE public.exam_sessions session
SET source_plan_name = plan.name,
    source_plan_status = plan.status,
    source_assessment_name = assessment.name,
    source_assessment_type = assessment.assessment_type,
    source_assessment_form = assessment.assessment_form,
    source_assessment_week = assessment.week_number,
    source_planned_start_date = GREATEST(
        semester.start_date,
        LEAST(semester.end_date, semester.start_date + ((GREATEST(assessment.week_number, 1) - 1) * 7))
    ),
    source_planned_end_date = LEAST(
        semester.end_date,
        semester.start_date + ((GREATEST(assessment.week_number, 1) - 1) * 7) + 6
    ),
    source_synced_at = COALESCE(session.updated_at, session.created_at),
    source_updated_at = GREATEST(
        COALESCE(plan.updated_at, session.created_at),
        COALESCE(assessment.updated_at, session.created_at)
    )
FROM public.academic_assessment_plans assessment
JOIN public.academic_training_plans plan ON plan.id = assessment.plan_id
JOIN public.semesters semester ON semester.id = assessment.semester_id
WHERE session.source_assessment_plan_id = assessment.id
  AND session.source_assessment_name IS NULL;

ALTER TABLE public.exam_teacher_unavailability
    ADD COLUMN IF NOT EXISTS unavailability_type varchar(40) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN IF NOT EXISTS status varchar(24) NOT NULL DEFAULT 'ACTIVE';

COMMENT ON COLUMN public.exam_sessions.source_synced_at IS
    'Time when the immutable GĐ3 source snapshot was copied into this exam session';
COMMENT ON COLUMN public.exam_sessions.schedule_deviation_reason IS
    'Required reason when the real exam date is outside the planned GĐ3 assessment week';
