-- Harden the education-plan workflow and assessment metadata.

ALTER TABLE public.academic_assessment_plans
    ADD COLUMN IF NOT EXISTS name character varying(255),
    ADD COLUMN IF NOT EXISTS assessment_form character varying(48),
    ADD COLUMN IF NOT EXISTS curriculum_item_ids text,
    ADD COLUMN IF NOT EXISTS result_method character varying(48);

UPDATE public.academic_assessment_plans
SET name = COALESCE(name, CASE assessment_type
        WHEN 'REGULAR' THEN 'Kiểm tra thường xuyên'
        WHEN 'MIDTERM' THEN 'Kiểm tra giữa kỳ'
        WHEN 'FINAL' THEN 'Kiểm tra cuối kỳ'
        WHEN 'PRACTICE' THEN 'Bài thực hành'
        WHEN 'PROJECT' THEN 'Bài dự án'
        ELSE 'Kế hoạch kiểm tra'
    END),
    assessment_form = COALESCE(assessment_form, 'WRITTEN'),
    result_method = COALESCE(result_method, 'SCORE');

ALTER TABLE public.academic_assessment_plans
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN assessment_form SET NOT NULL,
    ALTER COLUMN result_method SET NOT NULL;

ALTER TABLE public.academic_assessment_plans
    DROP CONSTRAINT IF EXISTS ck_assessment_plan_type;
ALTER TABLE public.academic_assessment_plans
    ADD CONSTRAINT ck_assessment_plan_type CHECK (assessment_type IN (
        'REGULAR', 'MIDTERM', 'FINAL', 'MAKEUP', 'PRACTICE', 'PROJECT'
    ));

-- A subject-week has one semantic meaning. Assessment plans share the same
-- EXAM marker instead of creating duplicate special-week records.
DELETE FROM public.academic_training_plan_special_weeks buffer_week
USING public.academic_training_plan_special_weeks exam_week
WHERE buffer_week.plan_subject_id = exam_week.plan_subject_id
  AND buffer_week.week_number = exam_week.week_number
  AND buffer_week.week_type = 'BUFFER'
  AND exam_week.week_type = 'EXAM';

ALTER TABLE public.academic_training_plan_special_weeks
    DROP CONSTRAINT IF EXISTS uk_training_special_week;
CREATE UNIQUE INDEX IF NOT EXISTS uk_training_special_week_number
    ON public.academic_training_plan_special_weeks(plan_subject_id, week_number);
