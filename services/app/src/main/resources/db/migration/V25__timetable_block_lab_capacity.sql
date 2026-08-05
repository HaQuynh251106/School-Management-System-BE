-- K10 and K11 A1-A5 have science blocks in the same opposite shift.
-- Eight laboratories are required to keep every three-period block conflict-free.
INSERT INTO rooms (id, code, name, capacity, active, room_type)
VALUES
    ('rm-lab-5', 'LAB5', 'Phong thi nghiem 5', 45, true, 'LAB'),
    ('rm-lab-6', 'LAB6', 'Phong thi nghiem 6', 45, true, 'LAB'),
    ('rm-lab-7', 'LAB7', 'Phong thi nghiem 7', 45, true, 'LAB'),
    ('rm-lab-8', 'LAB8', 'Phong thi nghiem 8', 45, true, 'LAB')
ON CONFLICT (id) DO UPDATE
SET code = EXCLUDED.code,
    name = EXCLUDED.name,
    capacity = EXCLUDED.capacity,
    active = true,
    room_type = 'LAB';
