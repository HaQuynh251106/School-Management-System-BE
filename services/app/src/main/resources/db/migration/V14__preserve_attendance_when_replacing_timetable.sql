-- Attendance is historical data, while timetable slots are replaceable weekly
-- templates. Keep attendance snapshots when an admin publishes a replacement.
ALTER TABLE public.attendance_records
    DROP CONSTRAINT IF EXISTS fk_attendance_slot;

ALTER TABLE public.attendance_records
    ADD CONSTRAINT fk_attendance_slot
    FOREIGN KEY (slot_id)
    REFERENCES public.timetable_slots(id)
    ON DELETE SET NULL;
