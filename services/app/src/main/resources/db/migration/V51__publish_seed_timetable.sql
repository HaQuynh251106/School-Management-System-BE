-- Existing demo databases were seeded before timetable version publishing existed.
-- Only the known demo slots are marked as published; real draft slots stay private.
UPDATE timetable_slots
SET published_plan_id = 'seed-published-2026-hk1'
WHERE published_plan_id IS NULL
  AND id IN ('tt-1', 'tt-2', 'tt-3', 'tt-4', 'tt-5', 'tt-6', 'tt-7', 'tt-8');
