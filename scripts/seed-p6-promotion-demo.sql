BEGIN;

-- Keep the target academic year readable in the UI.
UPDATE academic_years
SET name = 'Nam hoc 2027-2028'
WHERE id = 'ay-a795cde3c4'
  AND code = '2027-2028';

-- Target classes used by retained, grade-10 promoted, and grade-11 promoted students.
INSERT INTO classes (
    id, academic_year_id, code, grade_level, homeroom_teacher_id, name, student_count
)
VALUES
    ('c-2027-10a1', 'ay-a795cde3c4', '10A1', 'K10', 'u-t-chem',  'Lop 10A1', 0),
    ('c-2027-11a1', 'ay-a795cde3c4', '11A1', 'K11', 'u-t-civic', 'Lop 11A1', 0),
    ('c-2027-12a1', 'ay-a795cde3c4', '12A1', 'K12', 'u-t-civic', 'Lop 12A1', 0)
ON CONFLICT (academic_year_id, code) DO UPDATE
SET grade_level = EXCLUDED.grade_level,
    name = EXCLUDED.name,
    homeroom_teacher_id = EXCLUDED.homeroom_teacher_id;

-- Complete teaching assignments for both semesters and source classes.
WITH teacher_map(subject_id, teacher_id) AS (
    VALUES
        ('sj-math',  'u-t-math'),
        ('sj-lit',   'u-t-lit'),
        ('sj-eng',   'u-t-eng'),
        ('sj-phys',  'u-t-phys'),
        ('sj-chem',  'u-t-chem'),
        ('sj-bio',   'u-t-bio'),
        ('sj-hist',  'u-t-hist'),
        ('sj-geo',   'u-t-geo'),
        ('sj-it',    'u-t-it'),
        ('sj-pe',    'u-t-pe'),
        ('sj-civic', 'u-t-civic'),
        ('sj-def',   'u-t-defense')
),
source_classes(class_id, class_code) AS (
    VALUES ('c-10a1', '10A1'), ('c-11a1', '11A1')
),
source_semesters(semester_id) AS (
    VALUES ('sm-2026-1'), ('sm-2026-2')
)
INSERT INTO teacher_class_subjects (
    id, class_code, class_id, created_at, semester_id, status,
    subject_id, subject_name, teacher_id, teacher_name, updated_at, weekly_periods
)
SELECT
    'tcs-p6-' || substr(md5(sc.class_id || sem.semester_id || tm.subject_id), 1, 20),
    sc.class_code,
    sc.class_id,
    now(),
    sem.semester_id,
    'ACTIVE',
    tm.subject_id,
    s.name,
    tm.teacher_id,
    u.full_name,
    now(),
    2
FROM source_classes sc
CROSS JOIN source_semesters sem
CROSS JOIN teacher_map tm
JOIN subjects s ON s.id = tm.subject_id
JOIN users u ON u.id = tm.teacher_id
WHERE NOT EXISTS (
    SELECT 1
    FROM teacher_class_subjects existing
    WHERE existing.class_id = sc.class_id
      AND existing.semester_id = sem.semester_id
      AND existing.subject_id = tm.subject_id
      AND existing.status = 'ACTIVE'
);

-- Four required grade categories for every subject in both semesters.
WITH source_students AS (
    SELECT id, class_id, student_code
    FROM users
    WHERE role = 'STUDENT'
      AND status = 'ACTIVE'
      AND class_id IN ('c-10a1', 'c-11a1')
),
semester_subjects AS (
    SELECT DISTINCT class_id, semester_id, subject_id
    FROM teacher_class_subjects
    WHERE class_id IN ('c-10a1', 'c-11a1')
      AND semester_id IN ('sm-2026-1', 'sm-2026-2')
      AND status = 'ACTIVE'
)
INSERT INTO grades (
    id, category, category_name, note, recorded_at, score,
    semester_id, student_id, subject_id, subject_name
)
SELECT
    'g-p6-' || substr(md5(
        st.id || ss.semester_id || ss.subject_id || ec.code
    ), 1, 24),
    ec.code,
    ec.name,
    'P6 promotion demo data',
    timestamptz '2027-06-01 08:00:00+07',
    CASE
        WHEN st.student_code IN ('HS2610009', 'HS2610010') THEN 4.0
        ELSE round((
            7.0 + (
                get_byte(decode(substr(md5(
                    st.id || ss.semester_id || ss.subject_id || ec.code
                ), 1, 2), 'hex'), 0) % 21
            ) / 10.0
        )::numeric, 1)::double precision
    END,
    ss.semester_id,
    st.id,
    ss.subject_id,
    subject.name
FROM source_students st
JOIN semester_subjects ss ON ss.class_id = st.class_id
JOIN subjects subject ON subject.id = ss.subject_id
CROSS JOIN exam_categories ec
ON CONFLICT (id) DO UPDATE
SET score = EXCLUDED.score,
    category_name = EXCLUDED.category_name,
    subject_name = EXCLUDED.subject_name,
    note = EXCLUDED.note,
    recorded_at = EXCLUDED.recorded_at;

-- Attendance dates are inside each semester so the yearly preview can count them.
WITH source_students AS (
    SELECT id, class_id
    FROM users
    WHERE role = 'STUDENT'
      AND status = 'ACTIVE'
      AND class_id IN ('c-10a1', 'c-11a1')
),
attendance_days(semester_id, attendance_date, period_no) AS (
    VALUES
        ('sm-2026-1', date '2026-09-10', 1),
        ('sm-2026-1', date '2026-10-08', 2),
        ('sm-2026-1', date '2026-11-12', 3),
        ('sm-2026-1', date '2026-12-10', 4),
        ('sm-2026-1', date '2027-01-08', 5),
        ('sm-2026-2', date '2027-01-28', 1),
        ('sm-2026-2', date '2027-02-25', 2),
        ('sm-2026-2', date '2027-03-25', 3),
        ('sm-2026-2', date '2027-04-22', 4),
        ('sm-2026-2', date '2027-05-20', 5)
)
INSERT INTO attendance_records (
    id, class_id, date, note, period_no, slot_id, status, student_id, subject_name
)
SELECT
    'att-p6-' || substr(md5(
        st.id || ad.semester_id || ad.attendance_date::text
    ), 1, 22),
    st.class_id,
    ad.attendance_date,
    'P6 promotion demo data',
    ad.period_no,
    NULL,
    CASE
        WHEN get_byte(decode(substr(md5(
            st.id || ad.attendance_date::text
        ), 1, 2), 'hex'), 0) % 17 = 0 THEN 'LATE'
        ELSE 'PRESENT'
    END,
    st.id,
    'Sinh hoat lop'
FROM source_students st
CROSS JOIN attendance_days ad
ON CONFLICT (id) DO UPDATE
SET status = EXCLUDED.status,
    note = EXCLUDED.note;

-- Save decisions for every student. Finalization is intentionally done through the API.
INSERT INTO student_yearly_summaries (
    id, academic_year_id, attendance_rate, class_id, conduct_grade,
    finalized_at, finalized_by, reason, result, reviewed_at, reviewed_by,
    status, student_code, student_id, student_name, updated_at, yearly_average,
    next_class_id, progressed_at, progressed_by, progression_status
)
SELECT
    'sys-p6-' || substr(md5(u.id), 1, 20),
    'ay-2026',
    98.0,
    u.class_id,
    CASE
        WHEN u.student_code IN ('HS2610009', 'HS2610010') THEN 'PASS'
        ELSE 'GOOD'
    END,
    NULL,
    NULL,
    CASE
        WHEN u.student_code IN ('HS2610009', 'HS2610010')
            THEN 'Ket qua demo duoi nguong de kiem thu luu ban'
        ELSE NULL
    END,
    CASE
        WHEN u.student_code IN ('HS2610009', 'HS2610010') THEN 'RETAINED'
        ELSE 'PROMOTED'
    END,
    now(),
    'u-admin-1',
    'DRAFT',
    u.student_code,
    u.id,
    u.full_name,
    now(),
    CASE
        WHEN u.student_code IN ('HS2610009', 'HS2610010') THEN 4.0
        ELSE 8.0
    END,
    NULL,
    NULL,
    NULL,
    NULL
FROM users u
WHERE u.role = 'STUDENT'
  AND u.status = 'ACTIVE'
  AND u.class_id IN ('c-10a1', 'c-11a1')
ON CONFLICT (academic_year_id, student_id) DO UPDATE
SET class_id = EXCLUDED.class_id,
    student_code = EXCLUDED.student_code,
    student_name = EXCLUDED.student_name,
    attendance_rate = EXCLUDED.attendance_rate,
    yearly_average = EXCLUDED.yearly_average,
    conduct_grade = EXCLUDED.conduct_grade,
    result = EXCLUDED.result,
    reason = EXCLUDED.reason,
    reviewed_by = EXCLUDED.reviewed_by,
    reviewed_at = EXCLUDED.reviewed_at,
    status = 'DRAFT',
    finalized_by = NULL,
    finalized_at = NULL,
    next_class_id = NULL,
    progressed_at = NULL,
    progressed_by = NULL,
    progression_status = NULL,
    updated_at = now();

UPDATE classes c
SET student_count = (
    SELECT count(*)
    FROM users u
    WHERE u.role = 'STUDENT'
      AND u.status = 'ACTIVE'
      AND u.class_id = c.id
)
WHERE c.id IN ('c-10a1', 'c-11a1');

COMMIT;
