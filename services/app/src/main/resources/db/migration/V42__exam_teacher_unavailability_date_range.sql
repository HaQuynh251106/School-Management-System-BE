ALTER TABLE public.exam_teacher_unavailability
    ADD COLUMN IF NOT EXISTS end_date date;

UPDATE public.exam_teacher_unavailability
SET end_date = unavailable_date
WHERE end_date IS NULL;

ALTER TABLE public.exam_teacher_unavailability
    ALTER COLUMN end_date SET NOT NULL;

ALTER TABLE public.exam_teacher_unavailability
    ADD CONSTRAINT ck_exam_unavailability_dates
    CHECK (end_date >= unavailable_date);

CREATE INDEX IF NOT EXISTS idx_exam_teacher_unavailable_range
    ON public.exam_teacher_unavailability(teacher_id, unavailable_date, end_date);
