-- Capacity required by 30 classes when all grades share the same morning shift.
INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'rm-lab-3', 'LAB3', 'Phong thi nghiem 3', 45, true, 'LAB'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE lower(code) = lower('LAB3'));

INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'rm-it-2', 'IT2', 'Phong tin hoc 2', 45, true, 'COMPUTER'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE lower(code) = lower('IT2'));

INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'rm-gym-2', 'GYM2', 'San tap 2', 60, true, 'GYM'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE lower(code) = lower('GYM2'));

-- Math, Literature and English each have 120 periods/semester. Six teachers
-- are required to keep every teacher at 20 periods/week (5 periods x 4 days).
INSERT INTO users (id, username, password_hash, full_name, email, phone, role,
                   status, teacher_code, main_subject, created_at,
                   password_change_required, session_version, updated_at)
SELECT data.id, data.username, source.password_hash, data.full_name, data.email,
       NULL, 'TEACHER', 'ACTIVE', data.teacher_code, data.main_subject, now(),
       false, 0, now()
FROM (VALUES
    ('g0-teacher-math-4', 'gv.toan.04', 'Nguyen Minh Quan', 'gv.toan.04@sse.local', 'GV-TOAN-04', 'Toan'),
    ('g0-teacher-math-5', 'gv.toan.05', 'Pham Thanh Son', 'gv.toan.05@sse.local', 'GV-TOAN-05', 'Toan'),
    ('g0-teacher-math-6', 'gv.toan.06', 'Le Hoang Nam', 'gv.toan.06@sse.local', 'GV-TOAN-06', 'Toan'),
    ('g0-teacher-lit-4', 'gv.van.04', 'Tran Thu Ha', 'gv.van.04@sse.local', 'GV-VAN-04', 'Ngu van'),
    ('g0-teacher-lit-5', 'gv.van.05', 'Nguyen Ngoc Lan', 'gv.van.05@sse.local', 'GV-VAN-05', 'Ngu van'),
    ('g0-teacher-lit-6', 'gv.van.06', 'Do Mai Anh', 'gv.van.06@sse.local', 'GV-VAN-06', 'Ngu van'),
    ('g0-teacher-eng-4', 'gv.anh.04', 'Vu Minh Chau', 'gv.anh.04@sse.local', 'GV-ANH-04', 'Tieng Anh'),
    ('g0-teacher-eng-5', 'gv.anh.05', 'Bui Thanh Thao', 'gv.anh.05@sse.local', 'GV-ANH-05', 'Tieng Anh'),
    ('g0-teacher-eng-6', 'gv.anh.06', 'Hoang Bao Tram', 'gv.anh.06@sse.local', 'GV-ANH-06', 'Tieng Anh')
) AS data(id, username, full_name, email, teacher_code, main_subject)
CROSS JOIN LATERAL (
    SELECT password_hash FROM users
    WHERE role = 'TEACHER' AND status = 'ACTIVE'
    ORDER BY created_at NULLS LAST LIMIT 1
) source
WHERE NOT EXISTS (SELECT 1 FROM users existing WHERE existing.id = data.id);

INSERT INTO user_roles (id, user_id, role_id, assigned_at)
SELECT 'ur-' || teacher.id, teacher.id, 'role-teacher', now()
FROM users teacher
WHERE teacher.id IN (
    'g0-teacher-math-4', 'g0-teacher-math-5', 'g0-teacher-math-6',
    'g0-teacher-lit-4', 'g0-teacher-lit-5', 'g0-teacher-lit-6',
    'g0-teacher-eng-4', 'g0-teacher-eng-5', 'g0-teacher-eng-6')
ON CONFLICT (user_id, role_id) DO NOTHING;

WITH ranked AS (
    SELECT id, subject_id,
           row_number() OVER (PARTITION BY semester_id, subject_id ORDER BY class_code, id) AS rn
    FROM teacher_class_subjects
    WHERE status = 'ACTIVE' AND subject_id IN ('sj-math', 'sj-lit', 'sj-eng')
), balanced AS (
    SELECT id,
           CASE subject_id
               WHEN 'sj-math' THEN (ARRAY['u-t-math', 'g0-teacher-math-2', 'g0-teacher-math-3',
                                              'g0-teacher-math-4', 'g0-teacher-math-5', 'g0-teacher-math-6'])[((rn - 1) / 5) + 1]
               WHEN 'sj-lit' THEN (ARRAY['u-t-lit', 'g0-teacher-lit-2', 'g0-teacher-lit-3',
                                             'g0-teacher-lit-4', 'g0-teacher-lit-5', 'g0-teacher-lit-6'])[((rn - 1) / 5) + 1]
               WHEN 'sj-eng' THEN (ARRAY['u-t-eng', 'g0-teacher-eng-2', 'g0-teacher-eng-3',
                                             'g0-teacher-eng-4', 'g0-teacher-eng-5', 'g0-teacher-eng-6'])[((rn - 1) / 5) + 1]
           END AS teacher_id
    FROM ranked
)
UPDATE teacher_class_subjects assignment
SET teacher_id = balanced.teacher_id,
    teacher_name = teacher.full_name,
    updated_at = now()
FROM balanced
JOIN users teacher ON teacher.id = balanced.teacher_id
WHERE assignment.id = balanced.id;

-- Published slots and existing drafts are immutable scheduling snapshots.
-- New drafts use the balanced assignments above; old drafts must be regenerated.
