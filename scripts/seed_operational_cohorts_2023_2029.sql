\set ON_ERROR_STOP on
\pset pager off

BEGIN;
SET client_encoding = 'UTF8';
SET TIME ZONE 'Asia/Ho_Chi_Minh';
SELECT pg_advisory_xact_lock(hashtext('sse-operational-cohorts-2023-2029'));

\echo '1/12 - Lam sach mien du lieu hoc vu cu...'

CREATE TEMP TABLE _removed_people ON COMMIT DROP AS
SELECT id FROM users WHERE role IN ('STUDENT', 'PARENT');

DO $$
DECLARE
    target RECORD;
BEGIN
    FOR target IN
        SELECT DISTINCT c.table_name, c.column_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
        WHERE c.table_schema = 'public'
          AND t.table_type = 'BASE TABLE'
          AND c.table_name NOT IN ('users', 'flyway_schema_history')
          AND c.column_name IN ('user_id', 'student_id', 'parent_id', 'sender_id', 'recipient_id', 'registered_by')
        ORDER BY c.table_name, c.column_name
    LOOP
        EXECUTE format(
            'DELETE FROM public.%I WHERE %I IN (SELECT id FROM _removed_people)',
            target.table_name, target.column_name
        );
    END LOOP;
END $$;

TRUNCATE TABLE
    academic_documents,
    algorithm_assessments,
    algorithm_runs,
    assignment_attachments,
    assignment_lifecycle_logs,
    assignment_submission_attempts,
    assignment_submissions,
    assignments,
    attendance_change_logs,
    attendance_records,
    attendance_session_access,
    class_enrollments,
    class_placement_items,
    class_placement_runs,
    classes,
    conduct_evaluation_audits,
    conduct_evaluations,
    conduct_evidence,
    conduct_rule_sets,
    curriculum_requirement_history,
    curriculum_requirements,
    exam_candidates,
    exam_grading_assignments,
    exam_organization_plan_candidates,
    exam_organization_plan_rooms,
    exam_organization_plans,
    exam_periods,
    exam_proctor_plan_items,
    exam_proctor_plans,
    exam_result_change_logs,
    exam_results,
    exam_review_requests,
    exam_rooms,
    exam_schedule_classes,
    exam_schedules,
    exam_score_adjustments,
    exam_seating_plan_items,
    exam_seating_plans,
    grade_change_logs,
    gradebook_completion_audits,
    gradebook_locks,
    grades,
    leave_requests,
    lesson_diaries,
    report_card_audits,
    report_cards,
    room_allocation_plan_items,
    room_allocation_plans,
    student_class_transfers,
    student_interventions,
    student_yearly_summaries,
    teacher_load_registration_history,
    teacher_load_registration_windows,
    teacher_load_registrations,
    teacher_schedule_restriction_history,
    teacher_schedule_restriction_requests,
    teacher_workload_adjustments,
    teaching_assignment_history,
    teaching_assignment_plan_items,
    teaching_assignment_plans,
    teaching_assignments,
    timetable_change_requests,
    timetable_draft_slots,
    timetable_plan_slots,
    timetable_plans,
    timetable_publication_events,
    timetable_publication_recipients,
    timetable_publication_slots,
    timetable_publications,
    timetable_slots,
    year_rollover_plans,
    semesters,
    cohorts,
    academic_years
RESTART IDENTITY CASCADE;

DELETE FROM users WHERE role IN ('STUDENT', 'PARENT') OR id LIKE 'seed-teacher-%';
UPDATE users
SET class_id = NULL,
    class_name = NULL
WHERE role = 'TEACHER';

\echo '2/12 - Tao nam hoc, hoc ky va bon nien khoa...'

INSERT INTO academic_years(
    id, code, name, start_date, end_date, status,
    orientation_start_date, opening_date, instruction_weeks, auto_generated
) VALUES
    ('ay-2023-2024', '2023-2024', 'Năm học 2023-2024', DATE '2023-09-05', DATE '2024-05-31', 'CLOSED', DATE '2023-08-28', DATE '2023-09-05', 35, TRUE),
    ('ay-2024-2025', '2024-2025', 'Năm học 2024-2025', DATE '2024-09-05', DATE '2025-05-31', 'CLOSED', DATE '2024-08-28', DATE '2024-09-05', 35, TRUE),
    ('ay-2025-2026', '2025-2026', 'Năm học 2025-2026', DATE '2025-09-05', DATE '2026-05-31', 'CLOSED', DATE '2025-08-28', DATE '2025-09-05', 35, TRUE),
    ('ay-2026-2027', '2026-2027', 'Năm học 2026-2027', DATE '2026-08-17', DATE '2027-05-28', 'ACTIVE', DATE '2026-08-10', DATE '2026-08-17', 35, TRUE);

INSERT INTO semesters(
    id, academic_year_id, code, name, sequence, start_date, end_date,
    status, instruction_weeks, auto_generated
) VALUES
    ('sem-2023-1', 'ay-2023-2024', 'HK1', 'Học kỳ 1', 1, DATE '2023-09-05', DATE '2024-01-13', 'CLOSED', 18, TRUE),
    ('sem-2023-2', 'ay-2023-2024', 'HK2', 'Học kỳ 2', 2, DATE '2024-01-15', DATE '2024-05-31', 'CLOSED', 17, TRUE),
    ('sem-2024-1', 'ay-2024-2025', 'HK1', 'Học kỳ 1', 1, DATE '2024-09-05', DATE '2025-01-11', 'CLOSED', 18, TRUE),
    ('sem-2024-2', 'ay-2024-2025', 'HK2', 'Học kỳ 2', 2, DATE '2025-01-13', DATE '2025-05-31', 'CLOSED', 17, TRUE),
    ('sem-2025-1', 'ay-2025-2026', 'HK1', 'Học kỳ 1', 1, DATE '2025-09-05', DATE '2026-01-10', 'CLOSED', 18, TRUE),
    ('sem-2025-2', 'ay-2025-2026', 'HK2', 'Học kỳ 2', 2, DATE '2026-01-12', DATE '2026-05-31', 'CLOSED', 17, TRUE),
    ('sem-2026-1', 'ay-2026-2027', 'HK1', 'Học kỳ 1', 1, DATE '2026-08-17', DATE '2027-01-09', 'ACTIVE', 18, TRUE),
    ('sem-2026-2', 'ay-2026-2027', 'HK2', 'Học kỳ 2', 2, DATE '2027-01-11', DATE '2027-05-28', 'PLANNED', 17, TRUE);

INSERT INTO cohorts(
    id, code, name, entry_year, graduation_year, duration_years, status,
    entry_academic_year_id, created_at, created_by, completed_at
) VALUES
    ('cohort-2023-2026', '2023-2026', 'Niên khóa 2023-2026', 2023, 2026, 3, 'COMPLETED', 'ay-2023-2024', TIMESTAMPTZ '2023-08-01 08:00:00+07', 'SYSTEM', TIMESTAMPTZ '2026-06-15 09:00:00+07'),
    ('cohort-2024-2027', '2024-2027', 'Niên khóa 2024-2027', 2024, 2027, 3, 'ACTIVE', 'ay-2024-2025', TIMESTAMPTZ '2024-08-01 08:00:00+07', 'SYSTEM', NULL),
    ('cohort-2025-2028', '2025-2028', 'Niên khóa 2025-2028', 2025, 2028, 3, 'ACTIVE', 'ay-2025-2026', TIMESTAMPTZ '2025-08-01 08:00:00+07', 'SYSTEM', NULL),
    ('cohort-2026-2029', '2026-2029', 'Niên khóa 2026-2029', 2026, 2029, 3, 'ACTIVE', 'ay-2026-2027', TIMESTAMPTZ '2026-07-01 08:00:00+07', 'SYSTEM', NULL);

\echo '3/12 - Chuan hoa 30 phong hoc chinh va bo sung giao vien con thieu...'

INSERT INTO rooms(
    id, code, name, capacity, supports_morning, supports_afternoon,
    status, room_type, equipment_tags, home_room_eligible, notes
)
SELECT
    'room-main-' || lpad(n::text, 2, '0'),
    'A' || lpad(n::text, 3, '0'),
    'Phòng học A' || lpad(n::text, 3, '0'),
    45, TRUE, TRUE, 'ACTIVE', 'GENERAL',
    'máy chiếu, bảng viết, quạt trần', TRUE, 'Phòng học chính'
FROM generate_series(1, 30) n
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    capacity = EXCLUDED.capacity,
    supports_morning = TRUE,
    supports_afternoon = TRUE,
    status = 'ACTIVE',
    room_type = 'GENERAL',
    home_room_eligible = TRUE,
    notes = EXCLUDED.notes;

CREATE TEMP TABLE _teacher_additions(
    seq integer PRIMARY KEY,
    full_name text NOT NULL,
    subject_id text NOT NULL
) ON COMMIT DROP;

INSERT INTO _teacher_additions(seq, full_name, subject_id) VALUES
    (1,  'Nguyễn Thị Thu Cúc', 'sub-cong-nghe'),
    (2,  'Trần Minh Quang',    'sub-hoa-hoc'),
    (3,  'Lê Thị Bích Vân',   'sub-lich-su'),
    (4,  'Phạm Văn Khôi',     'sub-ngu-van'),
    (5,  'Hoàng Thị Thu Trang','sub-ngu-van'),
    (6,  'Đỗ Minh Tuấn',      'sub-sinh-hoc'),
    (7,  'Vũ Thị Thanh Mai',  'sub-tin-hoc'),
    (8,  'Bùi Quốc Việt',     'sub-tieng-anh'),
    (9,  'Đặng Thị Ngọc Anh', 'sub-tieng-anh'),
    (10, 'Nguyễn Mạnh Hùng',  'sub-toan'),
    (11, 'Trần Thu Phương',   'sub-toan'),
    (12, 'Lê Hoàng Nam',      'sub-vat-ly'),
    (13, 'Phạm Thị Minh Châu','sub-dia-ly');

INSERT INTO users(
    id, username, password_hash, full_name, email, phone, role, status,
    teacher_code, main_subject, main_subject_id, created_at,
    password_change_required, token_version, activation_status
)
SELECT
    'seed-teacher-' || lpad(a.seq::text, 2, '0'),
    'gv.bo.sung.' || lpad(a.seq::text, 2, '0'),
    admin.password_hash,
    a.full_name,
    'gv.bo.sung.' || lpad(a.seq::text, 2, '0') || '@truonghocso.local',
    '0988' || lpad(a.seq::text, 6, '0'),
    'TEACHER', 'ACTIVE',
    'GVBS' || lpad(a.seq::text, 3, '0'),
    s.name, s.id,
    TIMESTAMPTZ '2026-08-01 08:00:00+07',
    TRUE, 0, 'PENDING_EMAIL'
FROM _teacher_additions a
JOIN subjects s ON s.id = a.subject_id
CROSS JOIN LATERAL (
    SELECT password_hash FROM users WHERE role = 'ADMIN' ORDER BY created_at NULLS LAST LIMIT 1
) admin;

DO $$
DECLARE
    teacher_count integer;
BEGIN
    SELECT count(*) INTO teacher_count FROM users WHERE role = 'TEACHER' AND status = 'ACTIVE';
    IF teacher_count < 39 THEN
        RAISE EXCEPTION 'Can it nhat 39 giao vien de gan GVCN, hien co %', teacher_count;
    END IF;
END $$;

\echo '4/12 - Tao dinh muc 13 mon cho ca ba khoi va tat ca hoc ky...'

INSERT INTO curriculum_requirements(
    id, semester_id, grade_level, subject_id, subject_name,
    weekly_periods, created_at, updated_at
)
SELECT
    'curr-' || sem.id || '-k' || grade.grade_no || '-' || sub.id,
    sem.id,
    'K' || grade.grade_no,
    sub.id,
    sub.name,
    CASE
        WHEN sub.code IN ('CONG_NGHE', 'GDCD', 'LICH_SU', 'TIN_HOC', 'DIA_LY', 'SHL', 'SHTT') THEN 1
        WHEN sub.code = 'NGU_VAN' THEN CASE grade.grade_no WHEN 12 THEN 3 ELSE 4 END
        WHEN sub.code = 'TIENG_ANH' THEN CASE grade.grade_no WHEN 11 THEN 4 ELSE 3 END
        WHEN sub.code = 'TOAN' THEN 4
        WHEN sub.code = 'HOA_HOC' THEN CASE grade.grade_no WHEN 11 THEN 2 ELSE 3 END
        WHEN sub.code = 'VAT_LY' THEN CASE grade.grade_no WHEN 12 THEN 3 ELSE 2 END
        WHEN sub.code = 'SINH_HOC' THEN 2
        ELSE 1
    END,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM semesters sem
CROSS JOIN (VALUES (10), (11), (12)) grade(grade_no)
CROSS JOIN subjects sub
WHERE sub.status = 'ACTIVE'
  AND sub.code IN (
      'CONG_NGHE', 'GDCD', 'HOA_HOC', 'LICH_SU', 'NGU_VAN', 'SHL',
      'SHTT', 'SINH_HOC', 'TIN_HOC', 'TIENG_ANH', 'TOAN', 'VAT_LY', 'DIA_LY'
  );

DO $$
DECLARE
    invalid_count integer;
BEGIN
    SELECT count(*) INTO invalid_count
    FROM (
        SELECT semester_id, grade_level
        FROM curriculum_requirements
        GROUP BY semester_id, grade_level
        HAVING count(*) <> 13 OR sum(weekly_periods) <> 25
    ) invalid;
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'Dinh muc mon-khoi khong du 13 mon hoac 25 tiet/tuan (% nhom loi)', invalid_count;
    END IF;
END $$;

\echo '5/12 - Tao 104 lop lich su theo dung tung nien khoa...'

CREATE TEMP TABLE _class_blueprint(
    year_start integer NOT NULL,
    grade_no integer NOT NULL,
    cohort_id text NOT NULL
) ON COMMIT DROP;

INSERT INTO _class_blueprint VALUES
    (2023, 10, 'cohort-2023-2026'),
    (2024, 11, 'cohort-2023-2026'),
    (2025, 12, 'cohort-2023-2026'),
    (2024, 10, 'cohort-2024-2027'),
    (2025, 11, 'cohort-2024-2027'),
    (2026, 12, 'cohort-2024-2027'),
    (2025, 10, 'cohort-2025-2028'),
    (2026, 11, 'cohort-2025-2028');

CREATE TEMP TABLE _teachers ON COMMIT DROP AS
SELECT
    id,
    full_name,
    row_number() OVER (ORDER BY full_name, id)::integer AS rn
FROM users
WHERE role = 'TEACHER' AND status = 'ACTIVE';

CREATE TEMP TABLE _class_seed ON COMMIT DROP AS
WITH expanded AS (
    SELECT b.*, section_no
    FROM _class_blueprint b
    CROSS JOIN generate_series(1, 13) section_no
), numbered AS (
    SELECT
        e.*,
        row_number() OVER (
            PARTITION BY year_start ORDER BY grade_no, section_no
        )::integer AS year_ordinal
    FROM expanded e
)
SELECT
    'class-' || year_start || '-k' || grade_no || '-a' || lpad(section_no::text, 2, '0') AS id,
    'ay-' || year_start || '-' || (year_start + 1) AS academic_year_id,
    grade_no || 'A' || section_no AS code,
    'Lớp ' || grade_no || 'A' || section_no AS name,
    'K' || grade_no AS grade_level,
    cohort_id,
    section_no,
    year_ordinal,
    CASE
        WHEN grade_no = 10 AND section_no <= 7 THEN 'MORNING'
        WHEN grade_no = 11 AND section_no <= 6 THEN 'MORNING'
        WHEN grade_no = 12 AND section_no <= 7 THEN 'MORNING'
        ELSE 'AFTERNOON'
    END AS study_shift,
    CASE
        WHEN grade_no = 10 AND section_no <= 7 THEN section_no
        WHEN grade_no = 10 THEN section_no - 7
        WHEN grade_no = 11 AND section_no <= 6 THEN 7 + section_no
        WHEN grade_no = 11 THEN section_no
        WHEN grade_no = 12 AND section_no <= 7 THEN 13 + section_no
        ELSE 6 + section_no
    END AS room_no,
    year_start
FROM numbered;

INSERT INTO classes(
    id, academic_year_id, code, name, grade_level, cohort_id,
    student_count, planned_student_count, capacity, study_shift,
    room_id, room_code, homeroom_teacher_id, homeroom_teacher_name,
    homeroom_assigned_at, homeroom_assigned_by, status, auto_generated
)
SELECT
    c.id, c.academic_year_id, c.code, c.name, c.grade_level, c.cohort_id,
    0, 40, 40, c.study_shift,
    r.id, r.code, t.id, t.full_name,
    make_timestamptz(c.year_start, 8, 20, 8, 0, 0, 'Asia/Ho_Chi_Minh'),
    COALESCE((SELECT id FROM users WHERE role = 'ACADEMIC_STAFF' ORDER BY id LIMIT 1), 'SYSTEM'),
    CASE WHEN c.year_start < 2026 THEN 'CLOSED' ELSE 'ACTIVE' END,
    TRUE
FROM _class_seed c
JOIN _teachers t ON t.rn = c.year_ordinal
JOIN rooms r ON r.code = 'A' || lpad(c.room_no::text, 3, '0');

\echo '6/12 - Tao 2.000 hoc sinh va 1.600 phu huynh hop le UTF-8...'

CREATE TEMP TABLE _student_seed ON COMMIT DROP AS
SELECT
    cohort_start,
    student_no,
    ((cohort_start - 2023) * 500 + student_no)::integer AS global_no,
    'student-' || cohort_start || '-' || lpad(student_no::text, 4, '0') AS id,
    'cohort-' || cohort_start || '-' || (cohort_start + 3) AS cohort_id,
    'HS' || right(cohort_start::text, 2) || lpad(student_no::text, 4, '0') AS student_code,
    'hs.' || cohort_start || '.' || lpad(student_no::text, 4, '0') AS username,
    format('%s %s %s',
        (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Huỳnh','Phan','Vũ','Võ','Đặng','Bùi','Đỗ','Hồ','Ngô','Dương','Lý','Đinh','Mai','Trịnh','Đoàn'])[((student_no + cohort_start) % 20) + 1],
        (ARRAY['Văn','Thị','Đức','Minh','Gia','Thanh','Ngọc','Quốc','Khánh','Hoài','Tuấn','Thu','Hữu','Bảo','Anh'])[((student_no * 3 + cohort_start) % 15) + 1],
        (ARRAY['An','Anh','Bình','Chi','Dũng','Giang','Hà','Hải','Hân','Hiếu','Huy','Khang','Khánh','Linh','Long','Mai','Minh','Nam','Nga','Ngân','Ngọc','Nhung','Phúc','Phương','Quân','Quỳnh','Sơn','Thảo','Trang','Trung','Tú','Uyên','Việt','Vy'])[((student_no * 7 + cohort_start) % 34) + 1]
    ) AS full_name,
    CASE WHEN student_no % 2 = 0 THEN 'FEMALE' ELSE 'MALE' END AS gender,
    make_date(cohort_start - 15, ((student_no - 1) % 12) + 1, ((student_no - 1) % 27) + 1) AS date_of_birth
FROM (VALUES (2023), (2024), (2025), (2026)) cohorts(cohort_start)
CROSS JOIN generate_series(1, 500) student_no;

CREATE TEMP TABLE _parent_seed ON COMMIT DROP AS
SELECT
    parent_no,
    'parent-' || lpad(parent_no::text, 4, '0') AS id,
    'ph.' || lpad(parent_no::text, 4, '0') AS username,
    format('%s %s %s',
        (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Huỳnh','Phan','Vũ','Võ','Đặng','Bùi','Đỗ','Hồ','Ngô','Dương','Lý','Đinh','Mai','Trịnh','Đoàn'])[((parent_no * 5) % 20) + 1],
        CASE WHEN parent_no % 2 = 0 THEN 'Thị' ELSE 'Văn' END,
        (ARRAY['An','Bình','Cường','Dung','Dũng','Giang','Hạnh','Hiền','Hòa','Hùng','Hương','Lan','Linh','Long','Mai','Minh','Nam','Nga','Ngọc','Phương','Quân','Quang','Thảo','Thanh','Thu','Trang','Trung','Tuấn','Vân','Việt'])[((parent_no * 11) % 30) + 1]
    ) AS full_name,
    '09' || lpad(parent_no::text, 8, '0') AS phone
FROM generate_series(1, 1600) parent_no;

INSERT INTO users(
    id, username, password_hash, full_name, email, phone, role, status,
    created_at, date_of_birth, gender, place_of_birth, ethnicity, nationality,
    address, password_change_required, token_version, activation_status
)
SELECT
    p.id, p.username, admin.password_hash, p.full_name,
    p.username || '@phuhuynh.truonghocso.local', p.phone,
    'PARENT', 'ACTIVE', TIMESTAMPTZ '2026-07-01 08:00:00+07',
    make_date(1975 + (p.parent_no % 15), ((p.parent_no - 1) % 12) + 1, ((p.parent_no - 1) % 27) + 1),
    CASE WHEN p.parent_no % 2 = 0 THEN 'FEMALE' ELSE 'MALE' END,
    'Hà Nội', 'Kinh', 'Việt Nam',
    (20 + (p.parent_no % 180)) || ' đường Nguyễn Trãi, Hà Nội',
    TRUE, 0, 'PENDING_EMAIL'
FROM _parent_seed p
CROSS JOIN LATERAL (
    SELECT password_hash FROM users WHERE role = 'ADMIN' ORDER BY created_at NULLS LAST LIMIT 1
) admin;

INSERT INTO users(
    id, username, password_hash, full_name, email, role, status,
    student_code, created_at, date_of_birth, gender, place_of_birth,
    ethnicity, nationality, address, enrollment_date, cohort_id,
    student_status, graduated_at, graduation_academic_year_id,
    graduation_class_id, password_change_required, token_version, activation_status
)
SELECT
    s.id, s.username, admin.password_hash, s.full_name,
    s.username || '@hocsinh.truonghocso.local', 'STUDENT',
    CASE WHEN s.cohort_start = 2023 THEN 'INACTIVE' ELSE 'ACTIVE' END,
    s.student_code,
    make_timestamptz(s.cohort_start, 7, 15, 8, 0, 0, 'Asia/Ho_Chi_Minh'),
    s.date_of_birth, s.gender, 'Hà Nội', 'Kinh', 'Việt Nam',
    (10 + (s.student_no % 220)) || ' đường Quang Trung, Hà Nội',
    make_date(s.cohort_start, 9, 5), s.cohort_id,
    CASE
        WHEN s.cohort_start = 2023 THEN 'GRADUATED'
        WHEN s.cohort_start = 2026 THEN 'PENDING_PLACEMENT'
        ELSE 'ENROLLED'
    END,
    CASE WHEN s.cohort_start = 2023 THEN TIMESTAMPTZ '2026-06-15 09:00:00+07' END,
    CASE WHEN s.cohort_start = 2023 THEN 'ay-2025-2026' END,
    CASE WHEN s.cohort_start = 2023 THEN
        'class-2025-k12-a' || lpad((((s.student_no - 1) % 13) + 1)::text, 2, '0')
    END,
    TRUE, 0, 'PENDING_EMAIL'
FROM _student_seed s
CROSS JOIN LATERAL (
    SELECT password_hash FROM users WHERE role = 'ADMIN' ORDER BY created_at NULLS LAST LIMIT 1
) admin;

CREATE TEMP TABLE _student_parent_map ON COMMIT DROP AS
SELECT
    s.id AS student_id,
    CASE
        WHEN s.global_no <= 800 THEN ((s.global_no + 1) / 2)
        ELSE s.global_no - 400
    END::integer AS parent_no
FROM _student_seed s;

INSERT INTO parent_student(id, parent_id, student_id, primary_contact)
SELECT
    'ps-' || m.student_id,
    'parent-' || lpad(m.parent_no::text, 4, '0'),
    m.student_id,
    TRUE
FROM _student_parent_map m;

UPDATE users student
SET guardian_name = parent.full_name,
    guardian_phone = parent.phone
FROM _student_parent_map map
JOIN users parent
  ON parent.id = 'parent-' || lpad(map.parent_no::text, 4, '0')
WHERE student.id = map.student_id;

\echo '7/12 - Tao 4.000 luot ghi danh va lich su lop hoc...'

CREATE TEMP TABLE _enrollment_seed ON COMMIT DROP AS
SELECT
    s.*,
    year_offset,
    s.cohort_start + year_offset AS year_start,
    10 + year_offset AS grade_no,
    ((s.student_no - 1) % 13) + 1 AS section_no
FROM _student_seed s
CROSS JOIN LATERAL generate_series(
    0,
    CASE s.cohort_start
        WHEN 2023 THEN 2
        WHEN 2024 THEN 2
        WHEN 2025 THEN 1
        ELSE -1
    END
) year_offset;

INSERT INTO class_enrollments(
    id, student_id, class_id, academic_year_id, cohort_id,
    status, enrolled_at, ended_at
)
SELECT
    'enroll-' || e.cohort_start || '-' || e.student_no || '-' || e.year_start,
    e.id,
    'class-' || e.year_start || '-k' || e.grade_no || '-a' || lpad(e.section_no::text, 2, '0'),
    'ay-' || e.year_start || '-' || (e.year_start + 1),
    e.cohort_id,
    CASE
        WHEN e.cohort_start = 2023 AND e.year_offset = 2 THEN 'GRADUATED'
        WHEN e.year_start = 2026 THEN 'ACTIVE'
        ELSE 'TRANSFERRED'
    END,
    make_timestamptz(e.year_start, 9, 5, 7, 0, 0, 'Asia/Ho_Chi_Minh'),
    CASE WHEN e.year_start < 2026
        THEN make_timestamptz(e.year_start + 1, 6, 15, 9, 0, 0, 'Asia/Ho_Chi_Minh')
    END
FROM _enrollment_seed e;

UPDATE classes c
SET student_count = counts.total,
    planned_student_count = counts.total
FROM (
    SELECT class_id, count(*)::integer AS total
    FROM class_enrollments
    GROUP BY class_id
) counts
WHERE c.id = counts.class_id;

UPDATE users student
SET class_id = latest.class_id,
    class_name = latest.class_name
FROM (
    SELECT DISTINCT ON (e.student_id)
        e.student_id,
        e.class_id,
        c.name AS class_name
    FROM class_enrollments e
    JOIN classes c ON c.id = e.class_id
    JOIN academic_years y ON y.id = e.academic_year_id
    ORDER BY e.student_id, y.start_date DESC
) latest
WHERE student.id = latest.student_id;

UPDATE users teacher
SET class_id = current_class.id,
    class_name = current_class.name
FROM classes current_class
WHERE teacher.id = current_class.homeroom_teacher_id
  AND current_class.academic_year_id = 'ay-2026-2027';

\echo '8/12 - Phan cong dung chuyen mon cho 12 mon cua moi lop...'

CREATE TEMP TABLE _subject_teachers ON COMMIT DROP AS
SELECT
    s.id AS subject_id,
    t.id AS teacher_id,
    t.full_name AS teacher_name,
    row_number() OVER (PARTITION BY s.id ORDER BY t.full_name, t.id)::integer AS rn,
    count(*) OVER (PARTITION BY s.id)::integer AS teacher_count
FROM subjects s
JOIN users t
  ON t.role = 'TEACHER'
 AND t.status = 'ACTIVE'
 AND (
      t.main_subject_id = s.id
      OR lower(trim(COALESCE(t.main_subject, ''))) = lower(trim(s.name))
 )
WHERE s.status = 'ACTIVE'
  AND s.code NOT IN ('SHL', 'SHTT');

DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(s.name, ', ' ORDER BY s.name) INTO missing
    FROM subjects s
    WHERE s.status = 'ACTIVE'
      AND s.code NOT IN ('SHL', 'SHTT')
      AND NOT EXISTS (SELECT 1 FROM _subject_teachers st WHERE st.subject_id = s.id);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Khong co giao vien dung chuyen mon cho: %', missing;
    END IF;
END $$;

CREATE TEMP TABLE _assignment_seed ON COMMIT DROP AS
WITH base AS (
    SELECT
        c.id AS class_id,
        c.code AS class_code,
        c.homeroom_teacher_id,
        c.homeroom_teacher_name,
        sem.id AS semester_id,
        req.subject_id,
        req.subject_name,
        req.weekly_periods,
        row_number() OVER (
            PARTITION BY sem.id, req.subject_id ORDER BY c.grade_level, c.code
        )::integer AS subject_class_ordinal
    FROM classes c
    JOIN semesters sem ON sem.academic_year_id = c.academic_year_id
    JOIN curriculum_requirements req
      ON req.semester_id = sem.id AND req.grade_level = c.grade_level
    JOIN subjects sub ON sub.id = req.subject_id
    WHERE sub.code <> 'SHTT'
)
SELECT
    b.*,
    CASE WHEN sub.code = 'SHL' THEN b.homeroom_teacher_id ELSE st.teacher_id END AS teacher_id,
    CASE WHEN sub.code = 'SHL' THEN b.homeroom_teacher_name ELSE st.teacher_name END AS teacher_name
FROM base b
JOIN subjects sub ON sub.id = b.subject_id
LEFT JOIN LATERAL (
    SELECT candidate.teacher_id, candidate.teacher_name
    FROM _subject_teachers candidate
    WHERE candidate.subject_id = b.subject_id
      AND candidate.rn = ((b.subject_class_ordinal - 1) % candidate.teacher_count) + 1
    LIMIT 1
) st ON TRUE;

DO $$
DECLARE
    invalid integer;
BEGIN
    SELECT count(*) INTO invalid FROM _assignment_seed WHERE teacher_id IS NULL;
    IF invalid <> 0 THEN
        RAISE EXCEPTION 'Con % phan cong khong co giao vien', invalid;
    END IF;
END $$;

INSERT INTO teaching_assignments(
    id, class_id, class_code, semester_id, subject_id, subject_name,
    teacher_id, teacher_name, weekly_periods, effective_from, effective_to,
    status, assigned_by, assigned_at, updated_at, version
)
SELECT
    'ta-' || a.semester_id || '-' || a.class_id || '-' || a.subject_id,
    a.class_id, a.class_code, a.semester_id, a.subject_id, a.subject_name,
    a.teacher_id, a.teacher_name, a.weekly_periods,
    sem.start_date, sem.end_date,
    'ACTIVE',
    COALESCE((SELECT id FROM users WHERE role = 'ACADEMIC_STAFF' ORDER BY id LIMIT 1), 'SYSTEM'),
    sem.start_date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
    CURRENT_TIMESTAMP,
    0
FROM _assignment_seed a
JOIN semesters sem ON sem.id = a.semester_id;

\echo '9/12 - Sinh 288.000 dau diem day du cho cac nam da ket thuc...'

INSERT INTO grades(
    id, student_id, subject_id, subject_name, semester_id,
    category, category_name, assessment_index, score, note,
    recorded_at, created_at, created_by, updated_at, updated_by, version
)
SELECT
    'grade-' || e.id || '-' || sem.id || '-' || ta.subject_id || '-' || cat.code,
    e.id,
    ta.subject_id,
    ta.subject_name,
    sem.id,
    cat.code,
    cat.name,
    1,
    LEAST(10.0, ROUND((
        6.0
        + (abs(hashtext(e.id || '|' || sem.id || '|' || ta.subject_id)) % 34) / 10.0
        + CASE cat.code WHEN 'ORAL' THEN 0.2 WHEN '15M' THEN 0.1 WHEN 'FINAL' THEN 0.1 ELSE 0 END
    )::numeric, 1))::double precision,
    'Dữ liệu điểm đã hoàn tất và kiểm tra',
    (sem.end_date - CASE cat.code WHEN 'FINAL' THEN 3 WHEN 'MID' THEN 45 WHEN '15M' THEN 75 ELSE 95 END)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
    CURRENT_TIMESTAMP,
    ta.teacher_id,
    CURRENT_TIMESTAMP,
    ta.teacher_id,
    0
FROM _enrollment_seed e
JOIN semesters sem
  ON sem.academic_year_id = 'ay-' || e.year_start || '-' || (e.year_start + 1)
JOIN teaching_assignments ta
  ON ta.class_id = 'class-' || e.year_start || '-k' || e.grade_no || '-a' || lpad(e.section_no::text, 2, '0')
 AND ta.semester_id = sem.id
CROSS JOIN exam_categories cat
WHERE e.year_start < 2026
  AND cat.code IN ('ORAL', '15M', 'MID', 'FINAL');

INSERT INTO gradebook_locks(
    id, semester_id, class_id, subject_id, locked, reason,
    changed_by, changed_at, version
)
SELECT
    'lock-' || ta.id,
    ta.semester_id,
    ta.class_id,
    ta.subject_id,
    TRUE,
    'Sổ điểm lịch sử đã hoàn tất',
    ta.teacher_id,
    sem.end_date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
    0
FROM teaching_assignments ta
JOIN semesters sem ON sem.id = ta.semester_id
JOIN academic_years y ON y.id = sem.academic_year_id
WHERE y.start_date < DATE '2026-01-01';

INSERT INTO gradebook_completion_audits(
    id, semester_id, class_id, subject_id, action, note, actor_id, created_at
)
SELECT
    'audit-' || lock.id,
    lock.semester_id,
    lock.class_id,
    lock.subject_id,
    'COMPLETED',
    'Đã đối chiếu đủ đầu điểm trước khi khóa sổ',
    lock.changed_by,
    lock.changed_at
FROM gradebook_locks lock;

\echo '10/12 - Sinh 525.000 ban ghi chuyen can theo 175 ngay hoc moi nam...'

CREATE TEMP TABLE _school_days ON COMMIT DROP AS
SELECT academic_year_id, school_date, day_ordinal
FROM (
    SELECT
        y.id AS academic_year_id,
        d::date AS school_date,
        row_number() OVER (PARTITION BY y.id ORDER BY d)::integer AS day_ordinal
    FROM academic_years y
    CROSS JOIN LATERAL generate_series(y.start_date, y.end_date, INTERVAL '1 day') d
    WHERE extract(isodow FROM d) BETWEEN 1 AND 5
      AND y.start_date < DATE '2026-01-01'
) ranked
WHERE day_ordinal <= 175;

INSERT INTO attendance_records(
    id, student_id, class_id, slot_id, date, period_no,
    subject_name, status, note, version, updated_at, updated_by
)
SELECT
    'att-' || e.id || '-' || replace(day.school_date::text, '-', ''),
    e.id,
    'class-' || e.year_start || '-k' || e.grade_no || '-a' || lpad(e.section_no::text, 2, '0'),
    'daily-' || e.year_start || '-k' || e.grade_no || '-a' || lpad(e.section_no::text, 2, '0'),
    day.school_date,
    1,
    'Chuyên cần trong ngày',
    CASE
        WHEN abs(hashtext(e.id || '|' || day.school_date::text)) % 100 < 94 THEN 'PRESENT'
        WHEN abs(hashtext(e.id || '|' || day.school_date::text)) % 100 < 96 THEN 'LATE'
        WHEN abs(hashtext(e.id || '|' || day.school_date::text)) % 100 < 99 THEN 'ABSENT_EXCUSED'
        ELSE 'ABSENT_UNEXCUSED'
    END,
    CASE
        WHEN abs(hashtext(e.id || '|' || day.school_date::text)) % 100 < 94 THEN NULL
        WHEN abs(hashtext(e.id || '|' || day.school_date::text)) % 100 < 96 THEN 'Đi học muộn có ghi nhận'
        WHEN abs(hashtext(e.id || '|' || day.school_date::text)) % 100 < 99 THEN 'Nghỉ có phép'
        ELSE 'Nghỉ không phép'
    END,
    0,
    day.school_date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
    COALESCE((SELECT id FROM users WHERE role = 'ACADEMIC_STAFF' ORDER BY id LIMIT 1), 'SYSTEM')
FROM _enrollment_seed e
JOIN _school_days day
  ON day.academic_year_id = 'ay-' || e.year_start || '-' || (e.year_start + 1)
WHERE e.year_start < 2026;

\echo '11/12 - Tong ket nam, hanh kiem va phat hanh 3.000 hoc ba...'

CREATE TEMP TABLE _semester_averages ON COMMIT DROP AS
WITH subject_averages AS (
    SELECT
        g.student_id,
        g.semester_id,
        g.subject_id,
        ROUND((SUM(g.score * cat.weight) / NULLIF(SUM(cat.weight), 0))::numeric, 1)::double precision AS subject_average
    FROM grades g
    JOIN exam_categories cat ON cat.code = g.category
    GROUP BY g.student_id, g.semester_id, g.subject_id
)
SELECT
    student_id,
    semester_id,
    ROUND(avg(subject_average)::numeric, 1)::double precision AS semester_average
FROM subject_averages
GROUP BY student_id, semester_id;

INSERT INTO student_yearly_summaries(
    id, academic_year_id, student_id, student_name, class_id,
    semester_one_average, semester_two_average, average_score,
    conduct_grade, conduct_note, conduct_updated_by,
    promotion_status, missing_requirements, next_class_id,
    updated_at, finalized_at, finalized_by, version
)
SELECT
    'summary-' || e.year_start || '-' || e.id,
    'ay-' || e.year_start || '-' || (e.year_start + 1),
    e.id,
    e.full_name,
    'class-' || e.year_start || '-k' || e.grade_no || '-a' || lpad(e.section_no::text, 2, '0'),
    sem1.semester_average,
    sem2.semester_average,
    ROUND(((sem1.semester_average + 2 * sem2.semester_average) / 3.0)::numeric, 1)::double precision,
    CASE
        WHEN abs(hashtext(e.id || '|' || e.year_start)) % 100 < 72 THEN 'GOOD'
        WHEN abs(hashtext(e.id || '|' || e.year_start)) % 100 < 96 THEN 'FAIR'
        ELSE 'AVERAGE'
    END,
    'Đánh giá từ chuyên cần, ý thức học tập và tham gia hoạt động tập thể.',
    COALESCE((SELECT id FROM users WHERE role = 'ACADEMIC_STAFF' ORDER BY id LIMIT 1), 'SYSTEM'),
    CASE WHEN e.grade_no = 12 THEN 'GRADUATED' ELSE 'PROMOTED' END,
    NULL,
    CASE WHEN e.grade_no < 12 THEN
        'class-' || (e.year_start + 1) || '-k' || (e.grade_no + 1) || '-a' || lpad(e.section_no::text, 2, '0')
    END,
    make_timestamptz(e.year_start + 1, 6, 15, 9, 0, 0, 'Asia/Ho_Chi_Minh'),
    make_timestamptz(e.year_start + 1, 6, 15, 9, 0, 0, 'Asia/Ho_Chi_Minh'),
    COALESCE((SELECT id FROM users WHERE role = 'ACADEMIC_STAFF' ORDER BY id LIMIT 1), 'SYSTEM'),
    0
FROM _enrollment_seed e
JOIN _semester_averages sem1
  ON sem1.student_id = e.id
 AND sem1.semester_id = 'sem-' || e.year_start || '-1'
JOIN _semester_averages sem2
  ON sem2.student_id = e.id
 AND sem2.semester_id = 'sem-' || e.year_start || '-2'
WHERE e.year_start < 2026;

INSERT INTO report_cards(
    id, academic_year_id, student_id, class_id, homeroom_teacher_id,
    homeroom_comment, status, verification_code,
    submitted_at, submitted_by, approved_at, approved_by,
    locked_at, locked_by, published_at, published_by,
    created_at, updated_at, version
)
SELECT
    'report-' || summary.academic_year_id || '-' || summary.student_id,
    summary.academic_year_id,
    summary.student_id,
    summary.class_id,
    c.homeroom_teacher_id,
    CASE summary.conduct_grade
        WHEN 'GOOD' THEN 'Có ý thức tự giác, kết quả học tập và rèn luyện tốt.'
        WHEN 'FAIR' THEN 'Có tiến bộ, cần tiếp tục phát huy tính chủ động trong học tập.'
        ELSE 'Đã hoàn thành năm học, cần tăng cường tính chủ động và chuyên cần.'
    END,
    'PUBLISHED',
    upper('HB-' || replace(summary.academic_year_id, 'ay-', '') || '-' || replace(summary.student_id, 'student-', '')),
    summary.finalized_at - INTERVAL '5 days', c.homeroom_teacher_id,
    summary.finalized_at - INTERVAL '3 days', staff.id,
    summary.finalized_at - INTERVAL '1 day', staff.id,
    summary.finalized_at, staff.id,
    summary.finalized_at - INTERVAL '7 days', summary.finalized_at, 0
FROM student_yearly_summaries summary
JOIN classes c ON c.id = summary.class_id
CROSS JOIN LATERAL (
    SELECT COALESCE((SELECT id FROM users WHERE role = 'ACADEMIC_STAFF' ORDER BY id LIMIT 1), 'SYSTEM') AS id
) staff;

INSERT INTO report_card_audits(
    id, report_card_id, action, from_status, to_status, note, actor_id, created_at
)
SELECT
    'rc-audit-' || rc.id,
    rc.id,
    'PUBLISHED',
    'LOCKED',
    'PUBLISHED',
    'Học bạ đã được kiểm tra, khóa và phát hành chính thức.',
    rc.published_by,
    rc.published_at
FROM report_cards rc;

\echo '12/12 - Kiem tra bat bien du lieu truoc khi commit...'

DO $$
DECLARE
    actual bigint;
    invalid bigint;
BEGIN
    SELECT count(*) INTO actual FROM users WHERE role = 'STUDENT';
    IF actual <> 2000 THEN RAISE EXCEPTION 'Sai tong hoc sinh: %, can 2000', actual; END IF;

    SELECT count(*) INTO actual FROM users WHERE role = 'PARENT';
    IF actual <> 1600 THEN RAISE EXCEPTION 'Sai tong phu huynh: %, can 1600', actual; END IF;

    SELECT count(*) INTO invalid
    FROM cohorts cohort
    LEFT JOIN users student ON student.cohort_id = cohort.id AND student.role = 'STUDENT'
    GROUP BY cohort.id
    HAVING count(student.id) <> 500;
    IF invalid <> 0 THEN RAISE EXCEPTION 'Co nien khoa khong du 500 hoc sinh'; END IF;

    SELECT count(*) INTO actual FROM classes;
    IF actual <> 104 THEN RAISE EXCEPTION 'Sai tong lop lich su: %, can 104', actual; END IF;

    SELECT count(*) INTO actual FROM class_enrollments;
    IF actual <> 4000 THEN RAISE EXCEPTION 'Sai tong luot ghi danh: %, can 4000', actual; END IF;

    SELECT count(*) INTO actual
    FROM class_enrollments enrollment
    JOIN users student ON student.id = enrollment.student_id
    WHERE student.cohort_id = 'cohort-2026-2029';
    IF actual <> 0 THEN RAISE EXCEPTION 'Nien khoa 2026-2029 da bi phan lop (% luot)', actual; END IF;

    SELECT count(*) INTO actual
    FROM users
    WHERE cohort_id = 'cohort-2026-2029'
      AND role = 'STUDENT'
      AND (class_id IS NOT NULL OR student_status <> 'PENDING_PLACEMENT');
    IF actual <> 0 THEN RAISE EXCEPTION 'Ho so dau vao 2026-2029 khong o trang thai cho phan lop'; END IF;

    SELECT count(*) INTO actual FROM grades;
    IF actual <> 288000 THEN RAISE EXCEPTION 'Sai tong dau diem: %, can 288000', actual; END IF;

    SELECT count(*) INTO actual FROM attendance_records;
    IF actual <> 525000 THEN RAISE EXCEPTION 'Sai tong chuyen can: %, can 525000', actual; END IF;

    SELECT count(*) INTO actual FROM student_yearly_summaries;
    IF actual <> 3000 THEN RAISE EXCEPTION 'Sai tong ket nam: %, can 3000', actual; END IF;

    SELECT count(*) INTO actual FROM report_cards WHERE status = 'PUBLISHED';
    IF actual <> 3000 THEN RAISE EXCEPTION 'Sai hoc ba da phat hanh: %, can 3000', actual; END IF;

    SELECT count(*) INTO invalid
    FROM (
        SELECT e.id, count(g.id) AS grade_count
        FROM _enrollment_seed e
        LEFT JOIN grades g
          ON g.student_id = e.id
         AND g.semester_id IN ('sem-' || e.year_start || '-1', 'sem-' || e.year_start || '-2')
        WHERE e.year_start < 2026
        GROUP BY e.id, e.year_start
        HAVING count(g.id) <> 96
    ) broken;
    IF invalid <> 0 THEN RAISE EXCEPTION 'Co % ho so nam hoc khong du 96 dau diem', invalid; END IF;

    SELECT count(*) INTO invalid
    FROM (
        SELECT e.id, e.year_start, count(a.id) AS attendance_count
        FROM _enrollment_seed e
        LEFT JOIN attendance_records a
          ON a.student_id = e.id
         AND a.date BETWEEN make_date(e.year_start, 8, 1) AND make_date(e.year_start + 1, 7, 31)
        WHERE e.year_start < 2026
        GROUP BY e.id, e.year_start
        HAVING count(a.id) <> 175
    ) broken;
    IF invalid <> 0 THEN RAISE EXCEPTION 'Co % ho so nam hoc khong du 175 ngay chuyen can', invalid; END IF;

    SELECT count(*) INTO invalid
    FROM users
    WHERE role IN ('STUDENT', 'PARENT')
      AND (full_name LIKE '%?%' OR full_name LIKE '%�%');
    IF invalid <> 0 THEN RAISE EXCEPTION 'Con % ho so co dau hieu loi ma hoa ten', invalid; END IF;

    SELECT count(*) INTO invalid
    FROM classes c
    LEFT JOIN cohorts cohort ON cohort.id = c.cohort_id
    LEFT JOIN academic_years y ON y.id = c.academic_year_id
    WHERE cohort.id IS NULL OR y.id IS NULL;
    IF invalid <> 0 THEN RAISE EXCEPTION 'Con % lop mo coi nien khoa/nam hoc', invalid; END IF;
END $$;

COMMIT;

\echo '=== KET QUA NAP DU LIEU ==='
SELECT
    cohort.code AS nien_khoa,
    cohort.status,
    count(student.id) AS hoc_sinh,
    count(student.id) FILTER (WHERE student.student_status = 'PENDING_PLACEMENT') AS cho_phan_lop,
    count(student.id) FILTER (WHERE student.student_status = 'GRADUATED') AS da_tot_nghiep
FROM cohorts cohort
LEFT JOIN users student ON student.cohort_id = cohort.id AND student.role = 'STUDENT'
GROUP BY cohort.code, cohort.status, cohort.entry_year
ORDER BY cohort.entry_year;

SELECT
    y.code AS nam_hoc,
    y.status,
    (SELECT count(*) FROM classes c WHERE c.academic_year_id = y.id) AS so_lop,
    (SELECT count(DISTINCT e.student_id) FROM class_enrollments e
        WHERE e.academic_year_id = y.id AND e.status <> 'ROLLED_BACK') AS hoc_sinh_co_lop,
    (SELECT count(*) FROM student_yearly_summaries summary
        WHERE summary.academic_year_id = y.id) AS tong_ket_nam,
    (SELECT count(*) FROM report_cards rc
        WHERE rc.academic_year_id = y.id AND rc.status = 'PUBLISHED') AS hoc_ba_da_phat_hanh
FROM academic_years y
ORDER BY y.start_date;

SELECT
    (SELECT count(*) FROM grades) AS dau_diem,
    (SELECT count(*) FROM attendance_records) AS chuyen_can,
    (SELECT count(*) FROM student_yearly_summaries) AS tong_ket,
    (SELECT count(*) FROM report_cards WHERE status = 'PUBLISHED') AS hoc_ba_phat_hanh,
    (SELECT count(*) FROM parent_student) AS lien_ket_phu_huynh_hoc_sinh;
