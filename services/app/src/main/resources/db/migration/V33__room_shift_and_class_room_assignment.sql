ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS supports_morning boolean NOT NULL DEFAULT true;

ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS supports_afternoon boolean NOT NULL DEFAULT true;

ALTER TABLE classes
    ADD COLUMN IF NOT EXISTS room_id varchar(255);

ALTER TABLE classes
    ADD COLUMN IF NOT EXISTS room_code varchar(255);

ALTER TABLE classes
    DROP CONSTRAINT IF EXISTS fk_classes_room;

ALTER TABLE classes
    ADD CONSTRAINT fk_classes_room
        FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_classes_year_shift_room
    ON classes (academic_year_id, study_shift, room_id);

CREATE INDEX IF NOT EXISTS idx_classes_room_id
    ON classes (room_id);
