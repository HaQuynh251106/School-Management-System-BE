-- GĐ5 is the operational realization of assessment plans published in GĐ3.
-- Existing schedules remain readable, but new/published sessions must carry a source.
ALTER TABLE public.exam_sessions
    ADD COLUMN IF NOT EXISTS source_assessment_plan_id varchar(255),
    ADD COLUMN IF NOT EXISTS source_training_plan_id varchar(255),
    ADD COLUMN IF NOT EXISTS source_plan_version integer;

ALTER TABLE public.exam_sessions
    ADD CONSTRAINT fk_exam_session_source_assessment
    FOREIGN KEY (source_assessment_plan_id)
    REFERENCES public.academic_assessment_plans(id)
    ON DELETE RESTRICT;

ALTER TABLE public.exam_sessions
    ADD CONSTRAINT fk_exam_session_source_training_plan
    FOREIGN KEY (source_training_plan_id)
    REFERENCES public.academic_training_plans(id)
    ON DELETE RESTRICT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_exam_session_source_version
    ON public.exam_sessions(version_id, source_assessment_plan_id)
    WHERE source_assessment_plan_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_exam_session_source_assessment
    ON public.exam_sessions(source_assessment_plan_id);

COMMENT ON COLUMN public.exam_sessions.source_assessment_plan_id IS
    'Canonical GĐ3 assessment plan used to create this GĐ5 operational exam session';
COMMENT ON COLUMN public.exam_sessions.source_plan_version IS
    'Published GĐ3 plan version snapshot at the time the session was created';
