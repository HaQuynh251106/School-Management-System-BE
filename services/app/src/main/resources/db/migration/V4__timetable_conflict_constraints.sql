-- Database-level safety net for timetable conflict checks.

CREATE UNIQUE INDEX IF NOT EXISTS uk_timetable_class_period
    ON public.timetable_slots (class_id, semester_id, day_of_week, period_no)
    WHERE class_id IS NOT NULL AND semester_id IS NOT NULL AND day_of_week IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_timetable_teacher_period
    ON public.timetable_slots (teacher_id, semester_id, day_of_week, period_no)
    WHERE teacher_id IS NOT NULL AND semester_id IS NOT NULL AND day_of_week IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_timetable_room_period
    ON public.timetable_slots (room_code, semester_id, day_of_week, period_no)
    WHERE room_code IS NOT NULL AND semester_id IS NOT NULL AND day_of_week IS NOT NULL;
