ALTER TABLE public.timetable_makeup_proposals
    ADD COLUMN IF NOT EXISTS review_note varchar(1000);
