-- Drafts must be able to retain solver conflicts so Admin can inspect and fix them.
-- Publication is still blocked by AutomaticTimetableService validation, while the
-- live timetable keeps its strict uniqueness constraints.

ALTER TABLE public.timetable_draft_slots
    DROP CONSTRAINT IF EXISTS uk_timetable_draft_class_slot;
ALTER TABLE public.timetable_draft_slots
    DROP CONSTRAINT IF EXISTS uk_timetable_draft_teacher_slot;
ALTER TABLE public.timetable_draft_slots
    DROP CONSTRAINT IF EXISTS uk_timetable_draft_room_slot;

CREATE INDEX IF NOT EXISTS idx_timetable_draft_class_slot
    ON public.timetable_draft_slots (schedule_id, class_id, day_of_week, period_no);
CREATE INDEX IF NOT EXISTS idx_timetable_draft_teacher_slot
    ON public.timetable_draft_slots (schedule_id, teacher_id, day_of_week, period_no);
CREATE INDEX IF NOT EXISTS idx_timetable_draft_room_slot
    ON public.timetable_draft_slots (schedule_id, room_id, day_of_week, period_no);
