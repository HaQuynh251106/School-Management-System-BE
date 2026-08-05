ALTER TABLE public.exam_schedule_versions
    DROP CONSTRAINT IF EXISTS ck_exam_schedule_version_status;

ALTER TABLE public.exam_schedule_versions
    ADD CONSTRAINT ck_exam_schedule_version_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED', 'RECALLED'));
