-- Keep the first room assignment for a teacher inside one schedule and clear
-- legacy duplicates. The application service additionally rejects conflicts
-- across other simultaneous schedules.
UPDATE exam_rooms room
SET proctor_one_id = NULL, proctor_one_name = NULL
WHERE room.proctor_one_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM exam_rooms earlier
      WHERE earlier.schedule_id = room.schedule_id
        AND earlier.id < room.id
        AND room.proctor_one_id IN (earlier.proctor_one_id, earlier.proctor_two_id)
  );

UPDATE exam_rooms room
SET proctor_two_id = NULL, proctor_two_name = NULL
WHERE room.proctor_two_id IS NOT NULL
  AND (
      room.proctor_two_id = room.proctor_one_id
      OR EXISTS (
          SELECT 1 FROM exam_rooms earlier
          WHERE earlier.schedule_id = room.schedule_id
            AND earlier.id < room.id
            AND room.proctor_two_id IN (earlier.proctor_one_id, earlier.proctor_two_id)
      )
  );

ALTER TABLE exam_rooms
    ADD CONSTRAINT ck_exam_room_distinct_proctors
    CHECK (proctor_one_id IS NULL OR proctor_two_id IS NULL OR proctor_one_id <> proctor_two_id);

