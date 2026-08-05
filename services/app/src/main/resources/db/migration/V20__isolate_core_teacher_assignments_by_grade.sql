-- Existing published schedules keep their original teachers. New assignments use
-- dedicated Math/Literature/English teacher pairs per grade so a grade can be
-- regenerated independently without inheriting conflicts from legacy live slots.
INSERT INTO users (id, username, password_hash, full_name, email, phone, role,
                   status, teacher_code, main_subject, created_at,
                   password_change_required, session_version, updated_at)
SELECT data.id, data.username, source.password_hash, data.full_name, data.email,
       NULL, 'TEACHER', 'ACTIVE', data.teacher_code, data.main_subject, now(),
       false, 0, now()
FROM (VALUES
    ('g0-teacher-math-7', 'gv.toan.07', 'Dang Quoc Bao', 'gv.toan.07@sse.local', 'GV-TOAN-07', 'Toan'),
    ('g0-teacher-math-8', 'gv.toan.08', 'Bui Duc Huy', 'gv.toan.08@sse.local', 'GV-TOAN-08', 'Toan'),
    ('g0-teacher-math-9', 'gv.toan.09', 'Hoang Minh Duc', 'gv.toan.09@sse.local', 'GV-TOAN-09', 'Toan'),
    ('g0-teacher-lit-7', 'gv.van.07', 'Le Thu Huyen', 'gv.van.07@sse.local', 'GV-VAN-07', 'Ngu van'),
    ('g0-teacher-lit-8', 'gv.van.08', 'Pham Ngoc Anh', 'gv.van.08@sse.local', 'GV-VAN-08', 'Ngu van'),
    ('g0-teacher-lit-9', 'gv.van.09', 'Nguyen Thanh Ha', 'gv.van.09@sse.local', 'GV-VAN-09', 'Ngu van'),
    ('g0-teacher-eng-7', 'gv.anh.07', 'Tran Khanh Linh', 'gv.anh.07@sse.local', 'GV-ANH-07', 'Tieng Anh'),
    ('g0-teacher-eng-8', 'gv.anh.08', 'Do Bao Yen', 'gv.anh.08@sse.local', 'GV-ANH-08', 'Tieng Anh'),
    ('g0-teacher-eng-9', 'gv.anh.09', 'Nguyen Ha Phuong', 'gv.anh.09@sse.local', 'GV-ANH-09', 'Tieng Anh')
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
WHERE teacher.id LIKE 'g0-teacher-%-7'
   OR teacher.id LIKE 'g0-teacher-%-8'
   OR teacher.id LIKE 'g0-teacher-%-9'
ON CONFLICT (user_id, role_id) DO NOTHING;

WITH ranked AS (
    SELECT assignment.id, assignment.subject_id, class.grade_level,
           row_number() OVER (
               PARTITION BY assignment.semester_id, class.grade_level, assignment.subject_id
               ORDER BY class.code, assignment.id) AS rn
    FROM teacher_class_subjects assignment
    JOIN classes class ON class.id = assignment.class_id
    WHERE assignment.status = 'ACTIVE'
      AND assignment.subject_id IN ('sj-math', 'sj-lit', 'sj-eng')
), mapped AS (
    SELECT id,
       CASE subject_id || '-' || grade_level
         WHEN 'sj-math-K10' THEN (ARRAY['g0-teacher-math-4','g0-teacher-math-5'])[((rn - 1) / 5) + 1]
         WHEN 'sj-math-K11' THEN (ARRAY['g0-teacher-math-6','g0-teacher-math-7'])[((rn - 1) / 5) + 1]
         WHEN 'sj-math-K12' THEN (ARRAY['g0-teacher-math-8','g0-teacher-math-9'])[((rn - 1) / 5) + 1]
         WHEN 'sj-lit-K10' THEN (ARRAY['g0-teacher-lit-4','g0-teacher-lit-5'])[((rn - 1) / 5) + 1]
         WHEN 'sj-lit-K11' THEN (ARRAY['g0-teacher-lit-6','g0-teacher-lit-7'])[((rn - 1) / 5) + 1]
         WHEN 'sj-lit-K12' THEN (ARRAY['g0-teacher-lit-8','g0-teacher-lit-9'])[((rn - 1) / 5) + 1]
         WHEN 'sj-eng-K10' THEN (ARRAY['g0-teacher-eng-4','g0-teacher-eng-5'])[((rn - 1) / 5) + 1]
         WHEN 'sj-eng-K11' THEN (ARRAY['g0-teacher-eng-6','g0-teacher-eng-7'])[((rn - 1) / 5) + 1]
         WHEN 'sj-eng-K12' THEN (ARRAY['g0-teacher-eng-8','g0-teacher-eng-9'])[((rn - 1) / 5) + 1]
       END AS teacher_id
    FROM ranked
)
UPDATE teacher_class_subjects assignment
SET teacher_id = mapped.teacher_id,
    teacher_name = teacher.full_name,
    updated_at = now()
FROM mapped
JOIN users teacher ON teacher.id = mapped.teacher_id
WHERE assignment.id = mapped.id AND mapped.teacher_id IS NOT NULL;
