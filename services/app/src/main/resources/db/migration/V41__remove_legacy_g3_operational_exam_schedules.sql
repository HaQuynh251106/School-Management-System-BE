-- GĐ3 now owns assessment plans only. Operational dates, rooms and proctors
-- are created exclusively in GĐ5 from published assessment-plan sources.
DELETE FROM public.academic_exam_schedules;

COMMENT ON TABLE public.academic_exam_schedules IS
    'Legacy read-only table. Operational exam scheduling moved to GĐ5 exam_periods and exam_sessions.';
