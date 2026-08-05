CREATE TEMPORARY TABLE timetable_home_room_repairs ON COMMIT DROP AS
SELECT slot.id,
       room.code AS target_room_code
FROM public.timetable_slots AS slot
JOIN public.classes AS class ON class.id = slot.class_id
JOIN public.subjects AS subject ON subject.id = slot.subject_id
JOIN public.rooms AS room ON room.id = class.home_room_id
WHERE COALESCE(subject.required_room_type, 'GENERAL') = 'GENERAL'
  AND slot.room_code IS DISTINCT FROM room.code;

UPDATE public.timetable_slots AS slot
SET room_code = '__HOME_ROOM_REPAIR__' || slot.id
FROM timetable_home_room_repairs AS repair
WHERE repair.id = slot.id;

UPDATE public.timetable_slots AS slot
SET room_code = repair.target_room_code
FROM timetable_home_room_repairs AS repair
WHERE repair.id = slot.id;
