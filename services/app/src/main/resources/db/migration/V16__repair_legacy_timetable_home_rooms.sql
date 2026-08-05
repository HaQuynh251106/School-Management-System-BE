UPDATE public.timetable_draft_slots AS slot
SET room_id = class.home_room_id,
    room_code = room.code
FROM public.classes AS class,
     public.subjects AS subject,
     public.rooms AS room
WHERE slot.class_id = class.id
  AND slot.subject_id = subject.id
  AND room.id = class.home_room_id
  AND COALESCE(subject.required_room_type, 'GENERAL') = 'GENERAL'
  AND (slot.room_id IS DISTINCT FROM class.home_room_id
       OR slot.room_code IS DISTINCT FROM room.code);

UPDATE public.timetable_slots AS slot
SET room_code = room.code
FROM public.classes AS class,
     public.subjects AS subject,
     public.rooms AS room
WHERE slot.class_id = class.id
  AND slot.subject_id = subject.id
  AND room.id = class.home_room_id
  AND COALESCE(subject.required_room_type, 'GENERAL') = 'GENERAL'
  AND slot.room_code IS DISTINCT FROM room.code
  AND NOT EXISTS (
      SELECT 1
      FROM public.timetable_slots AS conflict
      WHERE conflict.id <> slot.id
        AND conflict.semester_id = slot.semester_id
        AND conflict.day_of_week = slot.day_of_week
        AND conflict.period_no = slot.period_no
        AND conflict.room_code = room.code
  );
