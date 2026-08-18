BEGIN;
SELECT pg_advisory_xact_lock(hashtext('sse-canonical-demo-seed'));

-- Two additional teachers per subject complement the twelve original demo teachers.
WITH teacher_seed AS (
    SELECT
        s.id AS subject_id,
        s.code AS subject_code,
        s.name AS subject_name,
        n AS teacher_no
    FROM subjects s
    CROSS JOIN generate_series(2, 3) n
)
INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    teacher_code, main_subject, created_at
)
SELECT
    'g0-teacher-' || lower(subject_code) || '-' || teacher_no,
    'demo.gv.' || lower(subject_code) || '.' || lpad(teacher_no::text, 2, '0'),
    '$2a$10$b4RB58rWGl1qjrdCKZfC8erWWGXWQtSfl6o.Ty3vP5G6tR.ndZu.O',
    'Demo Teacher ' || subject_code || ' ' || teacher_no,
    'demo.gv.' || lower(subject_code) || '.' || teacher_no || '@sse.local',
    '0918' || lpad((row_number() OVER (ORDER BY subject_code, teacher_no))::text, 6, '0'),
    'TEACHER',
    'ACTIVE',
    'G0-' || subject_code || '-' || lpad(teacher_no::text, 2, '0'),
    subject_name,
    now()
FROM teacher_seed
ON CONFLICT (id) DO UPDATE
SET full_name = excluded.full_name,
    email = excluded.email,
    phone = excluded.phone,
    status = 'ACTIVE',
    teacher_code = excluded.teacher_code,
    main_subject = excluded.main_subject;

-- Fill every class in the active academic year to exactly thirty active students.
WITH active_year AS (
    SELECT id, code
    FROM academic_years
    WHERE status = 'ACTIVE'
    ORDER BY start_date DESC NULLS LAST, id
    LIMIT 1
),
class_capacity AS (
    SELECT
        c.id AS class_id,
        c.code AS class_code,
        ay.code AS year_code,
        count(u.id)::integer AS existing_students
    FROM classes c
    JOIN active_year ay ON ay.id = c.academic_year_id
    LEFT JOIN users u
      ON u.class_id = c.id AND u.role = 'STUDENT' AND u.status <> 'DELETED'
    GROUP BY c.id, c.code, ay.code
),
missing_students AS (
    SELECT cc.*, sequence_no
    FROM class_capacity cc
    CROSS JOIN LATERAL generate_series(cc.existing_students + 1, 30) sequence_no
)
INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    student_code, class_id, class_name, created_at
)
SELECT
    'g0-student-' || regexp_replace(year_code, '[^0-9]', '', 'g') || '-' ||
        lower(class_code) || '-' || lpad(sequence_no::text, 3, '0'),
    'demo.hs.' || replace(year_code, '-', '') || '.' || lower(class_code) || '.' ||
        lpad(sequence_no::text, 3, '0'),
    '$2a$10$zNEL68yyW5SmLvlXAAtlneb9cYV0UbqH2jb4TstS/vjhxbxJ4r1Vq',
    'Demo Student ' || class_code || ' ' || lpad(sequence_no::text, 2, '0'),
    'demo.hs.' || replace(year_code, '-', '') || '.' || lower(class_code) || '.' ||
        lpad(sequence_no::text, 3, '0') || '@sse.local',
    '0927' || lpad((row_number() OVER (ORDER BY class_code, sequence_no))::text, 6, '0'),
    'STUDENT',
    'ACTIVE',
    'G0' || substr(regexp_replace(year_code, '[^0-9]', '', 'g'), 3, 2) ||
        replace(class_code, 'A', '') || lpad(sequence_no::text, 3, '0'),
    class_id,
    class_code,
    now()
FROM missing_students
ON CONFLICT (id) DO UPDATE
SET full_name = excluded.full_name,
    email = excluded.email,
    phone = excluded.phone,
    status = 'ACTIVE',
    class_id = excluded.class_id,
    class_name = excluded.class_name;

-- Pair students without a parent so one parent can manage one or two children.
CREATE TEMP TABLE g0_parent_pairs ON COMMIT DROP AS
WITH active_year AS (
    SELECT id
    FROM academic_years
    WHERE status = 'ACTIVE'
    ORDER BY start_date DESC NULLS LAST, id
    LIMIT 1
),
unlinked AS (
    SELECT
        u.id AS student_id,
        row_number() OVER (ORDER BY c.code, u.student_code, u.id) AS rn
    FROM users u
    JOIN classes c ON c.id = u.class_id
    JOIN active_year ay ON ay.id = c.academic_year_id
    WHERE u.role = 'STUDENT'
      AND u.status <> 'DELETED'
      AND NOT EXISTS (
          SELECT 1 FROM parent_student ps WHERE ps.student_id = u.id
      )
),
paired AS (
    SELECT
        student_id,
        ceil(rn / 2.0)::integer AS pair_no,
        min(student_id) OVER (PARTITION BY ceil(rn / 2.0)::integer) AS first_student_id
    FROM unlinked
)
SELECT
    student_id,
    'g0-parent-' || substr(md5(first_student_id), 1, 16) AS parent_id,
    pair_no
FROM paired;

INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status, created_at
)
SELECT DISTINCT
    parent_id,
    'demo.ph.' || substr(parent_id, 11),
    '$2a$10$/gDR2SwxnUaMU73MrnRYI.1jZa9PGgnIbb4EiG1F1zJmjQznr4jP2',
    'Demo Parent ' || lpad(pair_no::text, 4, '0'),
    'demo.ph.' || substr(parent_id, 11) || '@sse.local',
    '0937' || lpad(pair_no::text, 6, '0'),
    'PARENT',
    'ACTIVE',
    now()
FROM g0_parent_pairs
ON CONFLICT (id) DO UPDATE
SET full_name = excluded.full_name,
    email = excluded.email,
    phone = excluded.phone,
    status = 'ACTIVE';

INSERT INTO parent_student (id, parent_id, student_id, primary_contact)
SELECT
    'g0-ps-' || substr(md5(parent_id || ':' || student_id), 1, 20),
    parent_id,
    student_id,
    row_number() OVER (PARTITION BY parent_id ORDER BY student_id) = 1
FROM g0_parent_pairs
ON CONFLICT (parent_id, student_id) DO NOTHING;

-- Keep compatibility role assignments synchronized for every identity.
INSERT INTO user_roles (id, user_id, role_id)
SELECT 'ur-' || u.id || '-' || lower(u.role), u.id, r.id
FROM users u
JOIN roles r ON r.code = u.role
ON CONFLICT (user_id, role_id) DO NOTHING;

-- Assign three teachers per subject across the active year's classes and semesters.
CREATE TEMP TABLE g0_teacher_pool ON COMMIT DROP AS
SELECT
    s.id AS subject_id,
    s.name AS subject_name,
    u.id AS teacher_id,
    u.full_name AS teacher_name,
    row_number() OVER (PARTITION BY s.id ORDER BY
        CASE WHEN u.id LIKE 'u-t-%' THEN 0 ELSE 1 END, u.id) AS teacher_rank,
    count(*) OVER (PARTITION BY s.id) AS teacher_count
FROM subjects s
JOIN users u
  ON u.role = 'TEACHER'
 AND u.status = 'ACTIVE'
 AND lower(u.main_subject) = lower(s.name);

WITH active_year AS (
    SELECT id
    FROM academic_years
    WHERE status = 'ACTIVE'
    ORDER BY start_date DESC NULLS LAST, id
    LIMIT 1
),
ranked_classes AS (
    SELECT
        c.id,
        c.code,
        row_number() OVER (ORDER BY c.grade_level, c.code, c.id)::integer AS class_rank
    FROM classes c
    JOIN active_year ay ON ay.id = c.academic_year_id
),
targets AS (
    SELECT
        c.id AS class_id,
        c.code AS class_code,
        c.class_rank,
        s.id AS subject_id,
        s.name AS subject_name,
        sm.id AS semester_id,
        1 + mod(c.class_rank - 1, tp.teacher_count::integer) AS selected_rank
    FROM ranked_classes c
    CROSS JOIN subjects s
    JOIN semesters sm ON sm.academic_year_id = (SELECT id FROM active_year)
    JOIN (
        SELECT subject_id, max(teacher_count) AS teacher_count
        FROM g0_teacher_pool
        GROUP BY subject_id
    ) tp ON tp.subject_id = s.id
)
INSERT INTO teacher_class_subjects (
    id, teacher_id, teacher_name, class_id, class_code, subject_id, subject_name,
    semester_id, status, weekly_periods, created_at, updated_at
)
SELECT
    'g0-tcs-' || substr(md5(t.class_id || ':' || t.subject_id || ':' || t.semester_id), 1, 24),
    p.teacher_id,
    p.teacher_name,
    t.class_id,
    t.class_code,
    t.subject_id,
    t.subject_name,
    t.semester_id,
    'ACTIVE',
    CASE WHEN t.subject_id IN ('sj-math', 'sj-lit', 'sj-eng') THEN 4 ELSE 2 END,
    now(),
    now()
FROM targets t
JOIN g0_teacher_pool p
  ON p.subject_id = t.subject_id AND p.teacher_rank = t.selected_rank
ON CONFLICT (class_id, subject_id, semester_id) WHERE status = 'ACTIVE'
DO UPDATE SET
    teacher_id = excluded.teacher_id,
    teacher_name = excluded.teacher_name,
    class_code = excluded.class_code,
    subject_name = excluded.subject_name,
    weekly_periods = excluded.weekly_periods,
    updated_at = now();

-- Fill missing grade components without replacing any existing teacher-entered score.
WITH active_year AS (
    SELECT id
    FROM academic_years
    WHERE status = 'ACTIVE'
    ORDER BY start_date DESC NULLS LAST, id
    LIMIT 1
),
active_students AS (
    SELECT
        u.id,
        row_number() OVER (PARTITION BY u.class_id ORDER BY u.student_code, u.id) AS class_rank
    FROM users u
    JOIN classes c ON c.id = u.class_id
    JOIN active_year ay ON ay.id = c.academic_year_id
    WHERE u.role = 'STUDENT' AND u.status <> 'DELETED'
),
grade_targets AS (
    SELECT
        st.id AS student_id,
        s.id AS subject_id,
        s.name AS subject_name,
        sm.id AS semester_id,
        ec.code AS category,
        ec.name AS category_name
    FROM active_students st
    CROSS JOIN subjects s
    JOIN semesters sm ON sm.academic_year_id = (SELECT id FROM active_year)
    CROSS JOIN exam_categories ec
)
INSERT INTO grades (
    id, student_id, subject_id, subject_name, semester_id,
    category, category_name, score, note, recorded_at
)
SELECT
    'g0-grade-' || substr(md5(
        gt.student_id || ':' || gt.subject_id || ':' || gt.semester_id || ':' || gt.category
    ), 1, 24),
    gt.student_id,
    gt.subject_id,
    gt.subject_name,
    gt.semester_id,
    gt.category,
    gt.category_name,
    round((4.0 + mod(abs(hashtext(
        gt.student_id || ':' || gt.subject_id || ':' || gt.semester_id || ':' || gt.category
    )), 59) / 10.0)::numeric, 1)::double precision,
    'Canonical demo score',
    now()
FROM grade_targets gt
WHERE NOT EXISTS (
    SELECT 1
    FROM grades g
    WHERE g.student_id = gt.student_id
      AND g.subject_id = gt.subject_id
      AND g.semester_id = gt.semester_id
      AND g.category = gt.category
);

UPDATE classes c
SET student_count = x.actual_count
FROM (
    SELECT c2.id, count(u.id)::integer AS actual_count
    FROM classes c2
    LEFT JOIN users u
      ON u.class_id = c2.id AND u.role = 'STUDENT' AND u.status <> 'DELETED'
    GROUP BY c2.id
) x
WHERE x.id = c.id AND c.student_count <> x.actual_count;

-- A stable homeroom room per active class avoids fake room conflicts.
WITH active_year AS (
    SELECT id, code
    FROM academic_years
    WHERE status = 'ACTIVE'
    ORDER BY start_date DESC NULLS LAST, id
    LIMIT 1
)
INSERT INTO rooms (id, code, name, capacity)
SELECT
    'g0-room-' || substr(md5(c.id), 1, 20),
    'G0-' || replace(ay.code, '-', '') || '-' || c.code,
    'Demo room ' || c.code,
    40
FROM classes c
JOIN active_year ay ON ay.id = c.academic_year_id
ON CONFLICT (code) DO UPDATE
SET name = excluded.name,
    capacity = excluded.capacity;

-- DataSeeder creates a small legacy timetable (ids prefixed with `tt-`) so the
-- application can run without the canonical dataset. When the canonical demo
-- dataset is requested, remove only those legacy rows before installing the
-- complete school-wide timetable; otherwise their teacher/period slots can
-- collide with the deterministic `g0-slot-` rows below.
DELETE FROM timetable_slots
WHERE id LIKE 'tt-%';

-- Minimal conflict-free timetable for Student/Parent screens before auto-scheduling.
WITH active_year AS (
    SELECT id, code
    FROM academic_years
    WHERE status = 'ACTIVE'
    ORDER BY start_date DESC NULLS LAST, id
    LIMIT 1
),
ranked_classes AS (
    SELECT
        c.id,
        c.code,
        row_number() OVER (ORDER BY c.grade_level, c.code, c.id)::integer AS class_rank
    FROM classes c
    JOIN active_year ay ON ay.id = c.academic_year_id
),
ranked_subjects AS (
    SELECT
        s.id,
        row_number() OVER (ORDER BY s.code, s.id)::integer AS subject_rank
    FROM subjects s
),
slot_targets AS (
    SELECT
        tcs.id AS assignment_id,
        tcs.class_id,
        tcs.subject_id,
        tcs.subject_name,
        tcs.teacher_id,
        tcs.teacher_name,
        tcs.semester_id,
        'G0-' || replace(ay.code, '-', '') || '-' || rc.code AS room_code,
        mod(
            (rc.class_rank - 1)
            + ((rs.subject_rank - 1) * 7)
            + ((sm.sequence - 1) * 3),
            30
        )::integer AS time_index
    FROM teacher_class_subjects tcs
    JOIN ranked_classes rc ON rc.id = tcs.class_id
    JOIN ranked_subjects rs ON rs.id = tcs.subject_id
    JOIN semesters sm ON sm.id = tcs.semester_id
    CROSS JOIN active_year ay
    WHERE tcs.status = 'ACTIVE'
),
slots AS (
    SELECT
        st.*,
        CASE (st.time_index / 6)
            WHEN 0 THEN 'MON'
            WHEN 1 THEN 'TUE'
            WHEN 2 THEN 'WED'
            WHEN 3 THEN 'THU'
            ELSE 'FRI'
        END AS day_of_week,
        mod(st.time_index, 6) + 1 AS period_no
    FROM slot_targets st
)
INSERT INTO timetable_slots (
    id, class_id, subject_id, subject_name, teacher_id, teacher_name,
    room_code, day_of_week, period_no, start_time, end_time, semester_id
)
SELECT
    'g0-slot-' || substr(md5(assignment_id), 1, 24),
    class_id,
    subject_id,
    subject_name,
    teacher_id,
    teacher_name,
    room_code,
    day_of_week,
    period_no,
    (ARRAY[
        '07:00','07:50','08:45','09:35','10:25','13:30'
    ])[period_no],
    (ARRAY[
        '07:45','08:35','09:30','10:20','11:10','14:15'
    ])[period_no],
    semester_id
FROM slots
ON CONFLICT (class_id, semester_id, day_of_week, period_no)
    WHERE class_id IS NOT NULL AND semester_id IS NOT NULL AND day_of_week IS NOT NULL
DO UPDATE SET
    subject_id = excluded.subject_id,
    subject_name = excluded.subject_name,
    teacher_id = excluded.teacher_id,
    teacher_name = excluded.teacher_name,
    room_code = excluded.room_code,
    start_time = excluded.start_time,
    end_time = excluded.end_time;

COMMIT;
