-- Whole-school morning scheduling needs spare specialist-room capacity.
-- Exact 100% utilization is not feasible once teachers retain a weekly rest day.
INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'rm-it-3', 'IT3', 'Phong tin hoc 3', 45, true, 'COMPUTER'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE lower(code) = lower('IT3'));

INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'rm-gym-3', 'GYM3', 'San tap 3', 60, true, 'GYM'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE lower(code) = lower('GYM3'));

INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'rm-lab-4', 'LAB4', 'Phong thi nghiem 4', 45, true, 'LAB'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE lower(code) = lower('LAB4'));
