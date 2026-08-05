-- Nine two-period subjects need one dedicated teacher per grade. This keeps
-- each teacher at 20 periods/week and lets grades be regenerated independently.
INSERT INTO users (id, username, password_hash, full_name, email, phone, role,
                   status, teacher_code, main_subject, created_at,
                   password_change_required, session_version, updated_at)
SELECT data.id, data.username, source.password_hash, data.full_name,
       data.username || '@sse.local', NULL, 'TEACHER', 'ACTIVE',
       upper(replace(data.id, 'g0-teacher-', 'GV-')), data.main_subject,
       now(), false, 0, now()
FROM (VALUES
    ('g0-teacher-phys-4', 'gv.ly.04', 'Nguyen Quoc Viet', 'Vat ly'),
    ('g0-teacher-phys-5', 'gv.ly.05', 'Tran Minh Khoa', 'Vat ly'),
    ('g0-teacher-phys-6', 'gv.ly.06', 'Le Thanh Tung', 'Vat ly'),
    ('g0-teacher-chem-4', 'gv.hoa.04', 'Pham Thi Minh', 'Hoa hoc'),
    ('g0-teacher-chem-5', 'gv.hoa.05', 'Do Thu Trang', 'Hoa hoc'),
    ('g0-teacher-chem-6', 'gv.hoa.06', 'Nguyen Bao Chau', 'Hoa hoc'),
    ('g0-teacher-bio-4', 'gv.sinh.04', 'Vu Ngoc Anh', 'Sinh hoc'),
    ('g0-teacher-bio-5', 'gv.sinh.05', 'Hoang Thanh Mai', 'Sinh hoc'),
    ('g0-teacher-bio-6', 'gv.sinh.06', 'Bui Duc Anh', 'Sinh hoc'),
    ('g0-teacher-hist-4', 'gv.su.04', 'Le Quang Huy', 'Lich su'),
    ('g0-teacher-hist-5', 'gv.su.05', 'Nguyen Thu Phuong', 'Lich su'),
    ('g0-teacher-hist-6', 'gv.su.06', 'Tran Gia Bao', 'Lich su'),
    ('g0-teacher-geo-4', 'gv.dia.04', 'Pham Minh Hoang', 'Dia ly'),
    ('g0-teacher-geo-5', 'gv.dia.05', 'Do Khanh An', 'Dia ly'),
    ('g0-teacher-geo-6', 'gv.dia.06', 'Bui Thanh Lam', 'Dia ly'),
    ('g0-teacher-it-4', 'gv.tin.04', 'Nguyen Duc Thang', 'Tin hoc'),
    ('g0-teacher-it-5', 'gv.tin.05', 'Le Minh Tri', 'Tin hoc'),
    ('g0-teacher-it-6', 'gv.tin.06', 'Tran Hoai Nam', 'Tin hoc'),
    ('g0-teacher-pe-4', 'gv.theduc.04', 'Pham Van Son', 'Giao duc the chat'),
    ('g0-teacher-pe-5', 'gv.theduc.05', 'Nguyen Tuan Anh', 'Giao duc the chat'),
    ('g0-teacher-pe-6', 'gv.theduc.06', 'Hoang Duc Long', 'Giao duc the chat'),
    ('g0-teacher-civic-4', 'gv.gdkt.04', 'Le Mai Huong', 'GDKT va PL'),
    ('g0-teacher-civic-5', 'gv.gdkt.05', 'Tran Thu Thuy', 'GDKT va PL'),
    ('g0-teacher-civic-6', 'gv.gdkt.06', 'Nguyen Ngoc Ha', 'GDKT va PL'),
    ('g0-teacher-def-4', 'gv.gdqp.04', 'Do Quoc Trung', 'GDQP-AN'),
    ('g0-teacher-def-5', 'gv.gdqp.05', 'Bui Minh Tien', 'GDQP-AN'),
    ('g0-teacher-def-6', 'gv.gdqp.06', 'Tran Viet Dung', 'GDQP-AN')
) AS data(id, username, full_name, main_subject)
CROSS JOIN LATERAL (
    SELECT password_hash FROM users
    WHERE role = 'TEACHER' AND status = 'ACTIVE'
    ORDER BY created_at NULLS LAST LIMIT 1
) source
WHERE NOT EXISTS (SELECT 1 FROM users existing WHERE existing.id = data.id);

INSERT INTO user_roles (id, user_id, role_id, assigned_at)
SELECT 'ur-' || teacher.id, teacher.id, 'role-teacher', now()
FROM users teacher
WHERE teacher.id ~ '^g0-teacher-(phys|chem|bio|hist|geo|it|pe|civic|def)-[456]$'
ON CONFLICT (user_id, role_id) DO NOTHING;

WITH mapped AS (
    SELECT assignment.id,
           'g0-teacher-' ||
           CASE assignment.subject_id
             WHEN 'sj-phys' THEN 'phys'
             WHEN 'sj-chem' THEN 'chem'
             WHEN 'sj-bio' THEN 'bio'
             WHEN 'sj-hist' THEN 'hist'
             WHEN 'sj-geo' THEN 'geo'
             WHEN 'sj-it' THEN 'it'
             WHEN 'sj-pe' THEN 'pe'
             WHEN 'sj-civic' THEN 'civic'
             WHEN 'sj-def' THEN 'def'
           END || '-' ||
           CASE class.grade_level WHEN 'K10' THEN '4' WHEN 'K11' THEN '5' WHEN 'K12' THEN '6' END
           AS teacher_id
    FROM teacher_class_subjects assignment
    JOIN classes class ON class.id = assignment.class_id
    WHERE assignment.status = 'ACTIVE'
      AND assignment.subject_id IN (
        'sj-phys','sj-chem','sj-bio','sj-hist','sj-geo',
        'sj-it','sj-pe','sj-civic','sj-def')
)
UPDATE teacher_class_subjects assignment
SET teacher_id = mapped.teacher_id,
    teacher_name = teacher.full_name,
    updated_at = now()
FROM mapped
JOIN users teacher ON teacher.id = mapped.teacher_id
WHERE assignment.id = mapped.id;
