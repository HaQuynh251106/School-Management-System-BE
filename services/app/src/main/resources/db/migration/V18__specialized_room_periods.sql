ALTER TABLE public.teacher_class_subjects
    ADD COLUMN IF NOT EXISTS specialized_room_periods integer NOT NULL DEFAULT 0;

UPDATE public.teacher_class_subjects AS assignment
SET specialized_room_periods = CASE
    WHEN upper(coalesce(subject.required_room_type, 'GENERAL')) = 'LAB'
        THEN least(1, coalesce(assignment.weekly_periods, 1))
    WHEN upper(coalesce(subject.required_room_type, 'GENERAL')) IN
        ('COMPUTER', 'GYM', 'MUSIC', 'ART')
        THEN coalesce(assignment.weekly_periods, 1)
    ELSE 0
END
FROM public.subjects AS subject
WHERE subject.id = assignment.subject_id;

ALTER TABLE public.teacher_class_subjects
    DROP CONSTRAINT IF EXISTS chk_tcs_specialized_room_periods;

ALTER TABLE public.teacher_class_subjects
    ADD CONSTRAINT chk_tcs_specialized_room_periods CHECK (
        specialized_room_periods >= 0
        AND specialized_room_periods <= coalesce(weekly_periods, 1)
    );

ALTER TABLE public.timetable_draft_slots
    ADD COLUMN IF NOT EXISTS required_room_type character varying(40)
        NOT NULL DEFAULT 'GENERAL';

UPDATE public.timetable_draft_slots AS slot
SET required_room_type = CASE
    WHEN upper(coalesce(room.room_type, 'GENERAL')) = 'GENERAL' THEN 'GENERAL'
    ELSE upper(room.room_type)
END
FROM public.rooms AS room
WHERE room.id = slot.room_id;
