ALTER TABLE timetable_draft_slots
    DROP CONSTRAINT IF EXISTS ck_timetable_draft_source;

ALTER TABLE timetable_draft_slots
    ADD CONSTRAINT ck_timetable_draft_source
    CHECK (source IN (
        'AUTO', 'AUTO_BLOCK', 'FIXED_ACTIVITY', 'MANUAL', 'MOVED'
    ));
