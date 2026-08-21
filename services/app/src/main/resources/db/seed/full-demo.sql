BEGIN;
SELECT pg_advisory_xact_lock(hashtext('sse-full-demo-seed-2027-2028'));

-- ---------------------------------------------------------------------------
-- Reference catalog and active school year
-- ---------------------------------------------------------------------------
INSERT INTO grade_levels (code, name, numeric_level, display_order, active)
VALUES ('K10', 'Khối 10', 10, 10, true),
       ('K11', 'Khối 11', 11, 11, true),
       ('K12', 'Khối 12', 12, 12, true)
ON CONFLICT (code) DO UPDATE SET name = excluded.name, active = true;

INSERT INTO exam_categories (id, code, name, weight)
VALUES ('fd-ec-oral', 'ORAL', 'Điểm thường xuyên', 1),
       ('fd-ec-15m', '15M', 'Điểm định kỳ ngắn', 1),
       ('fd-ec-mid', 'MID', 'Giữa kỳ', 2),
       ('fd-ec-final', 'FINAL', 'Cuối kỳ', 3)
ON CONFLICT (code) DO UPDATE SET name = excluded.name, weight = excluded.weight;

INSERT INTO academic_years (id, code, name, start_date, end_date, status)
VALUES ('fd-ay-2026', '2026-2027', 'Năm học 2026-2027 · dữ liệu chuyển lớp',
        DATE '2026-09-01', DATE '2027-05-31', 'CLOSED'),
       ('fd-ay-2027', '2027-2028', 'Năm học 2027-2028',
        DATE '2027-09-01', DATE '2028-05-31', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET code = excluded.code, name = excluded.name,
    start_date = excluded.start_date, end_date = excluded.end_date,
    status = excluded.status;

INSERT INTO semesters (id, academic_year_id, code, name, sequence, start_date, end_date, status)
VALUES ('fd-sem-2026-1', 'fd-ay-2026', 'HK1', 'Học kỳ I 2026-2027', 1,
        DATE '2026-09-01', DATE '2027-01-31', 'CLOSED'),
       ('fd-sem-2026-2', 'fd-ay-2026', 'HK2', 'Học kỳ II 2026-2027', 2,
        DATE '2027-02-01', DATE '2027-05-31', 'CLOSED'),
       ('fd-sem-2027-1', 'fd-ay-2027', 'HK1', 'Học kỳ I', 1, DATE '2027-09-01', DATE '2028-01-31', 'ACTIVE'),
       ('fd-sem-2027-2', 'fd-ay-2027', 'HK2', 'Học kỳ II', 2, DATE '2028-02-01', DATE '2028-05-31', 'PLANNED')
ON CONFLICT (id) DO UPDATE SET name = excluded.name, sequence = excluded.sequence,
    start_date = excluded.start_date, end_date = excluded.end_date, status = excluded.status;

INSERT INTO teacher_staffing_policies (
    id, academic_year_id, school_type, weekly_teaching_norm,
    teaching_weeks, teacher_class_ratio, created_at, updated_at
)
VALUES ('fd-staffing-policy-2027', 'fd-ay-2027', 'PUBLIC_REGULAR', 17, 35, 2.25, now(), now())
ON CONFLICT (academic_year_id) DO UPDATE SET
    school_type = excluded.school_type,
    weekly_teaching_norm = excluded.weekly_teaching_norm,
    teaching_weeks = excluded.teaching_weeks,
    teacher_class_ratio = excluded.teacher_class_ratio,
    updated_at = now();

INSERT INTO subjects (
    id, code, name, coefficient, active, required_room_type, subject_type,
    department_name, assessment_method, facility_note
)
VALUES
    ('fd-sub-math', 'MATH', 'Toán', 1, true, 'GENERAL', 'MANDATORY', 'Tổ Toán', 'SCORE', null),
    ('fd-sub-lit', 'LIT', 'Ngữ văn', 1, true, 'GENERAL', 'MANDATORY', 'Tổ Ngữ văn', 'SCORE', null),
    ('fd-sub-eng', 'ENG', 'Tiếng Anh', 1, true, 'GENERAL', 'MANDATORY', 'Tổ Ngoại ngữ', 'SCORE', null),
    ('fd-sub-phys', 'PHYS', 'Vật lý', 1, true, 'LAB', 'MANDATORY', 'Tổ Khoa học tự nhiên', 'SCORE', 'Có tiết thực hành phòng bộ môn'),
    ('fd-sub-chem', 'CHEM', 'Hóa học', 1, true, 'LAB', 'MANDATORY', 'Tổ Khoa học tự nhiên', 'SCORE', 'Có tiết thực hành phòng bộ môn'),
    ('fd-sub-bio', 'BIO', 'Sinh học', 1, true, 'LAB', 'MANDATORY', 'Tổ Khoa học tự nhiên', 'SCORE', 'Có tiết thực hành phòng bộ môn'),
    ('fd-sub-hist', 'HIST', 'Lịch sử', 1, true, 'GENERAL', 'MANDATORY', 'Tổ Khoa học xã hội', 'SCORE', null),
    ('fd-sub-geo', 'GEO', 'Địa lý', 1, true, 'GENERAL', 'MANDATORY', 'Tổ Khoa học xã hội', 'SCORE', null),
    ('fd-sub-civic', 'CIVIC', 'Giáo dục công dân', 1, true, 'GENERAL', 'MANDATORY', 'Tổ Khoa học xã hội', 'SCORE', null),
    ('fd-sub-pe', 'PE', 'Giáo dục thể chất', 1, true, 'GYM', 'MANDATORY', 'Tổ Giáo dục thể chất', 'COMMENT', 'Học tại sân tập hoặc nhà đa năng'),
    -- AutomaticTimetableService dùng hai ID canonical này khi tự tạo tiết cố định.
    ('sj-flag', 'CHAOCO', 'Chào cờ', 1, true, 'GENERAL', 'EDUCATIONAL_ACTIVITY', 'Tổ Hoạt động giáo dục', 'COMMENT', null),
    ('sj-homeroom', 'SHL', 'Sinh hoạt lớp', 1, true, 'GENERAL', 'EDUCATIONAL_ACTIVITY', 'Tổ Hoạt động giáo dục', 'COMMENT', null)
ON CONFLICT (code) DO UPDATE SET name = excluded.name, coefficient = excluded.coefficient,
    active = true, required_room_type = excluded.required_room_type,
    subject_type = excluded.subject_type, department_name = excluded.department_name,
    assessment_method = excluded.assessment_method, facility_note = excluded.facility_note;

WITH class_room(class_no, code, name) AS (
    VALUES (1, 'P101', 'Phòng học 10A1'), (2, 'P102', 'Phòng học 10A2'),
           (3, 'P201', 'Phòng học 11A1'), (4, 'P202', 'Phòng học 11A2'),
           (5, 'P301', 'Phòng học 12A1'), (6, 'P302', 'Phòng học 12A2'),
           (7, 'P103', 'Phòng học 10A3'), (8, 'P104', 'Phòng học 10A4'),
           (9, 'P105', 'Phòng học 10A5'), (10, 'P106', 'Phòng học 10A6'),
           (11, 'P107', 'Phòng học 10A7'), (12, 'P108', 'Phòng học 10A8'),
           (13, 'P109', 'Phòng học 10A9'), (14, 'P110', 'Phòng học 10A10'),
           (15, 'P203', 'Phòng học 11A3'), (16, 'P204', 'Phòng học 11A4'),
           (17, 'P205', 'Phòng học 11A5'), (18, 'P206', 'Phòng học 11A6'),
           (19, 'P207', 'Phòng học 11A7'), (20, 'P208', 'Phòng học 11A8'),
           (21, 'P209', 'Phòng học 11A9'), (22, 'P210', 'Phòng học 11A10'),
           (23, 'P303', 'Phòng học 12A3'), (24, 'P304', 'Phòng học 12A4'),
           (25, 'P305', 'Phòng học 12A5'), (26, 'P306', 'Phòng học 12A6'),
           (27, 'P307', 'Phòng học 12A7'), (28, 'P308', 'Phòng học 12A8'),
           (29, 'P309', 'Phòng học 12A9'), (30, 'P310', 'Phòng học 12A10')
)
INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'fd-room-general-' || lpad(class_no::text, 2, '0'), code, name,
       CASE WHEN class_no BETWEEN 7 AND 14 THEN 36
            WHEN class_no BETWEEN 15 AND 22 THEN 40
            WHEN class_no BETWEEN 23 AND 30 THEN 42
            ELSE 40 END,
       true, 'GENERAL'
FROM class_room
ON CONFLICT (code) DO UPDATE SET name = excluded.name, capacity = excluded.capacity,
    active = true, room_type = 'GENERAL';

WITH class_room(class_no, code, name) AS (
    VALUES (1, 'TN101', 'Phòng thí nghiệm 10A1'), (2, 'TN102', 'Phòng thí nghiệm 10A2'),
           (3, 'TN201', 'Phòng thí nghiệm 11A1'), (4, 'TN202', 'Phòng thí nghiệm 11A2'),
           (5, 'TN301', 'Phòng thí nghiệm 12A1'), (6, 'TN302', 'Phòng thí nghiệm 12A2')
)
INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'fd-room-lab-' || lpad(class_no::text, 2, '0'), code, name, 40, true, 'LAB'
FROM class_room
ON CONFLICT (code) DO UPDATE SET name = excluded.name, capacity = 40, active = true, room_type = 'LAB';

WITH class_room(class_no, code, name) AS (
    VALUES (1, 'TD101', 'Sân tập 10A1'), (2, 'TD102', 'Sân tập 10A2'),
           (3, 'TD201', 'Sân tập 11A1'), (4, 'TD202', 'Sân tập 11A2'),
           (5, 'TD301', 'Sân tập 12A1'), (6, 'TD302', 'Sân tập 12A2')
)
INSERT INTO rooms (id, code, name, capacity, active, room_type)
SELECT 'fd-room-gym-' || lpad(class_no::text, 2, '0'), code, name, 60, true, 'GYM'
FROM class_room
ON CONFLICT (code) DO UPDATE SET name = excluded.name, capacity = 60, active = true, room_type = 'GYM';

INSERT INTO rooms (id, code, name, capacity, active, room_type)
VALUES ('fd-room-exam-01', 'P401', 'Phòng thi P401', 40, true, 'GENERAL')
ON CONFLICT (code) DO UPDATE SET name = excluded.name, capacity = 40, active = true;

-- Phòng riêng cho lớp nguồn đã kết thúc; không chiếm phòng chủ nhiệm của năm hiện hành.
INSERT INTO rooms (id, code, name, capacity, active, room_type)
VALUES ('fd-room-archive-01', 'P-CU-01', 'Phòng lớp nguồn 2026-2027', 40, false, 'GENERAL')
ON CONFLICT (code) DO UPDATE SET name = excluded.name, capacity = 40, active = false;

-- ---------------------------------------------------------------------------
-- Identities. Passwords below are BCrypt only; plaintext is printed by the
-- PowerShell wrapper and never stored in PostgreSQL.
-- ---------------------------------------------------------------------------
INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    created_at, updated_at, password_change_required, session_version
)
VALUES
    ('fd-admin-001', 'demo.admin.01', '$2a$10$uiIN7ah2rIcamZdyUH/yM.KX3IuQ70t80d.OC3KibBIH2MfKhPu2.',
     'Quản trị Demo 01', 'demo.admin.01@sse.local', '0901000001', 'ADMIN', 'ACTIVE', now(), now(), false, 0),
    ('fd-admin-002', 'demo.admin.02', '$2a$10$uiIN7ah2rIcamZdyUH/yM.KX3IuQ70t80d.OC3KibBIH2MfKhPu2.',
     'Quản trị Demo 02', 'demo.admin.02@sse.local', '0901000002', 'ADMIN', 'ACTIVE', now(), now(), false, 0)
ON CONFLICT (id) DO UPDATE SET password_hash = excluded.password_hash,
    full_name = excluded.full_name, email = excluded.email, phone = excluded.phone,
    status = excluded.status, updated_at = now();

INSERT INTO academic_promotion_policies (
    id, academic_year_id, minimum_yearly_average, minimum_conduct_grade,
    subject_minimum_score, maximum_subjects_below_minimum,
    minimum_attendance_rate, updated_by, updated_at
)
VALUES ('fd-promotion-policy-2026', 'fd-ay-2026', 5.0, 'PASS', 5.0, 0, 80.0,
        'fd-admin-001', now()),
       ('fd-promotion-policy-2027', 'fd-ay-2027', 5.0, 'PASS', 3.5, 2, 80.0,
        'fd-admin-001', now())
ON CONFLICT (academic_year_id) DO UPDATE SET
    minimum_yearly_average = excluded.minimum_yearly_average,
    minimum_conduct_grade = excluded.minimum_conduct_grade,
    subject_minimum_score = excluded.subject_minimum_score,
    maximum_subjects_below_minimum = excluded.maximum_subjects_below_minimum,
    minimum_attendance_rate = excluded.minimum_attendance_rate,
    updated_by = excluded.updated_by,
    updated_at = now();

WITH subject_catalog AS (
    SELECT row_number() OVER (ORDER BY display_order)::integer AS subject_no, code, name
    FROM (VALUES
        (1, 'MATH', 'Toán'), (2, 'LIT', 'Ngữ văn'), (3, 'ENG', 'Tiếng Anh'),
        (4, 'PHYS', 'Vật lý'), (5, 'CHEM', 'Hóa học'), (6, 'BIO', 'Sinh học'),
        (7, 'HIST', 'Lịch sử'), (8, 'GEO', 'Địa lý'),
        (9, 'CIVIC', 'Giáo dục công dân'), (10, 'PE', 'Giáo dục thể chất'),
        (11, 'CHAOCO', 'Chào cờ'), (12, 'SHL', 'Sinh hoạt lớp')
    ) catalog(display_order, code, name)
), teacher_seed AS (
    SELECT n, c.code, c.name,
           1 + ((n - 1) / 12) AS subject_teacher_no
    FROM generate_series(1, 36) n
    JOIN subject_catalog c ON c.subject_no = 1 + mod(n - 1, 12)
)
INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    teacher_code, main_subject, created_at, updated_at,
    password_change_required, session_version, date_of_birth, gender,
    nationality, address
)
SELECT 'fd-teacher-' || lpad(n::text, 3, '0'),
       'demo.gv.' || lpad(n::text, 3, '0'),
       '$2a$10$/ka71A3CXkDW/g9swoW8PuV.lCHj2GTLJ1.cHW3k6KmIiUMweLtEy',
       'Giáo viên ' || name || ' ' || subject_teacher_no,
       'demo.gv.' || lpad(n::text, 3, '0') || '@sse.local',
       '0911' || lpad(n::text, 6, '0'), 'TEACHER', 'ACTIVE',
       'GV27' || lpad(n::text, 4, '0'), name, now(), now(), false, 0,
       DATE '1982-01-01' + (n * interval '120 days'),
       CASE WHEN mod(n, 2) = 0 THEN 'FEMALE' ELSE 'MALE' END,
       'Việt Nam', 'Thành phố Hà Nội'
FROM teacher_seed
ON CONFLICT (id) DO UPDATE SET password_hash = excluded.password_hash,
    full_name = excluded.full_name, email = excluded.email, phone = excluded.phone,
    status = 'ACTIVE', teacher_code = excluded.teacher_code,
    main_subject = excluded.main_subject, updated_at = now();

INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    teacher_code, main_subject, created_at, updated_at, password_change_required
)
VALUES
    ('fd-teacher-037', 'demo.gv.locked', '$2a$10$/ka71A3CXkDW/g9swoW8PuV.lCHj2GTLJ1.cHW3k6KmIiUMweLtEy',
     'Giáo viên Demo đã khóa', 'demo.gv.locked@sse.local', '0911000037', 'TEACHER', 'LOCKED',
     'GV270037', 'Toán', now(), now(), false),
    ('fd-teacher-038', 'demo.gv.pending', '$2a$10$/ka71A3CXkDW/g9swoW8PuV.lCHj2GTLJ1.cHW3k6KmIiUMweLtEy',
     'Giáo viên Demo chờ kích hoạt', 'demo.gv.pending@sse.local', '0911000038', 'TEACHER', 'PENDING',
     'GV270038', 'Ngữ văn', now(), now(), true)
ON CONFLICT (id) DO UPDATE SET status = excluded.status, updated_at = now();

WITH class_seed(class_no, id, code, name, grade_level) AS (
    VALUES (1, 'fd-class-10a1', '10A1', 'Lớp 10A1', 'K10'),
           (2, 'fd-class-10a2', '10A2', 'Lớp 10A2', 'K10'),
           (3, 'fd-class-11a1', '11A1', 'Lớp 11A1', 'K11'),
           (4, 'fd-class-11a2', '11A2', 'Lớp 11A2', 'K11'),
           (5, 'fd-class-12a1', '12A1', 'Lớp 12A1', 'K12'),
           (6, 'fd-class-12a2', '12A2', 'Lớp 12A2', 'K12')
)
INSERT INTO classes (
    id, academic_year_id, code, grade_level, homeroom_teacher_id, name,
    student_count, max_students, home_room_id, expected_student_count, status
)
SELECT id, 'fd-ay-2027', code, grade_level,
       'fd-teacher-' || lpad(class_no::text, 3, '0'), name,
       10, 40, 'fd-room-general-' || lpad(class_no::text, 2, '0'), 10, 'ACTIVE'
FROM class_seed
ON CONFLICT (id) DO UPDATE SET homeroom_teacher_id = excluded.homeroom_teacher_id,
    student_count = 10, max_students = 40, home_room_id = excluded.home_room_id,
    expected_student_count = 10, status = 'ACTIVE';

-- Chuẩn bị sẵn 24 lớp dự kiến để người dùng kiểm thử thao tác kích hoạt và mở
-- rộng quy mô. Các lớp này ở INACTIVE nên không làm sai định mức 2,25
-- giáo viên/lớp hoặc bị bộ xếp lịch tự động đưa vào lịch khi chưa đủ nhân sự.
-- Khi trường kích hoạt thêm lớp, cần tăng giáo viên theo phân tích định biên.
WITH planned_class AS (
    SELECT row_number() OVER (ORDER BY grade_no, section_no)::integer + 6 AS class_no,
           grade_no, section_no,
           'K' || grade_no AS grade_level,
           grade_no || 'A' || section_no AS code
    FROM (VALUES (10),(11),(12)) grade(grade_no)
    CROSS JOIN generate_series(3,10) section(section_no)
)
INSERT INTO classes (
    id, academic_year_id, code, grade_level, homeroom_teacher_id, name,
    student_count, max_students, home_room_id, expected_student_count, status
)
SELECT 'fd-class-' || lower(code), 'fd-ay-2027', code, grade_level,
       'fd-teacher-' || lpad(class_no::text, 3, '0'), 'Lớp ' || code,
       0,
       CASE grade_no WHEN 10 THEN 36 WHEN 11 THEN 40 ELSE 42 END,
       'fd-room-general-' || lpad(class_no::text, 2, '0'), 0, 'INACTIVE'
FROM planned_class
ON CONFLICT (id) DO UPDATE SET homeroom_teacher_id=excluded.homeroom_teacher_id,
    student_count=0, max_students=excluded.max_students,
    home_room_id=excluded.home_room_id, expected_student_count=0,
    status='INACTIVE';

-- Một lớp nguồn đã kết thúc để Admin có thể thực hiện trọn luồng:
-- rà soát -> chốt -> công bố kết quả -> chuyển học sinh sang năm 2027-2028.
INSERT INTO classes (
    id, academic_year_id, code, grade_level, homeroom_teacher_id, name,
    student_count, max_students, home_room_id, expected_student_count, status
)
VALUES ('fd-class-2026-11a1', 'fd-ay-2026', '11A1-2627', 'K11',
        'fd-teacher-001', 'Lớp 11A1 · năm 2026-2027', 1, 40,
        'fd-room-archive-01', 1, 'CLOSED')
ON CONFLICT (id) DO UPDATE SET homeroom_teacher_id = excluded.homeroom_teacher_id,
    student_count = 1, max_students = 40, home_room_id = excluded.home_room_id,
    expected_student_count = 1, status = 'CLOSED';

WITH class_seed(class_no, id, code) AS (
    VALUES (1, 'fd-class-10a1', '10A1'), (2, 'fd-class-10a2', '10A2'),
           (3, 'fd-class-11a1', '11A1'), (4, 'fd-class-11a2', '11A2'),
           (5, 'fd-class-12a1', '12A1'), (6, 'fd-class-12a2', '12A2')
), student_seed AS (
    SELECT n, 1 + ((n - 1) / 10) AS class_no
    FROM generate_series(1, 60) n
)
INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    student_code, class_id, class_name, created_at, updated_at,
    password_change_required, session_version, date_of_birth, gender,
    place_of_birth, ethnicity, nationality, address, enrollment_date,
    guardian_name, guardian_phone
)
SELECT 'fd-student-' || lpad(s.n::text, 3, '0'),
       'demo.hs.' || lpad(s.n::text, 3, '0'),
       '$2a$10$F3g3JAvND2cU2O9VXo8.1OR6AlBTGi.wLWkpnJ7e4wHxUkuDOtL.a',
       'Học sinh Demo ' || lpad(s.n::text, 3, '0'),
       'demo.hs.' || lpad(s.n::text, 3, '0') || '@sse.local',
       '0922' || lpad(s.n::text, 6, '0'), 'STUDENT', 'ACTIVE',
       'HS27' || lpad(s.n::text, 4, '0'), c.id, c.code, now(), now(), false, 0,
       CASE c.code
           WHEN '10A1' THEN DATE '2012-01-01'
           WHEN '10A2' THEN DATE '2012-06-01'
           WHEN '11A1' THEN DATE '2011-01-01'
           WHEN '11A2' THEN DATE '2011-06-01'
           WHEN '12A1' THEN DATE '2010-01-01'
           ELSE DATE '2010-06-01' END + (mod(s.n, 180) * interval '1 day'),
       CASE WHEN mod(s.n, 2) = 0 THEN 'FEMALE' ELSE 'MALE' END,
       'Hà Nội', 'Kinh', 'Việt Nam', 'Quận Cầu Giấy, Hà Nội', DATE '2027-09-01',
       'Phụ huynh Demo ' || lpad((CASE WHEN s.n <= 24 THEN ceil(s.n / 2.0) ELSE s.n - 12 END)::integer::text, 3, '0'),
       '0933' || lpad((CASE WHEN s.n <= 24 THEN ceil(s.n / 2.0) ELSE s.n - 12 END)::integer::text, 6, '0')
FROM student_seed s
JOIN class_seed c ON c.class_no = s.class_no
ON CONFLICT (id) DO UPDATE SET password_hash = excluded.password_hash,
    full_name = excluded.full_name, email = excluded.email, phone = excluded.phone,
    status = 'ACTIVE', student_code = excluded.student_code, class_id = excluded.class_id,
    class_name = excluded.class_name, guardian_name = excluded.guardian_name,
    guardian_phone = excluded.guardian_phone, updated_at = now();

-- HS060 là hồ sơ chuyển lớp chưa xử lý. Học sinh vẫn thuộc tổng 60 tài khoản
-- ACTIVE nhưng chưa có enrollment ở năm đích, nhờ đó chức năng chuyển lớp có
-- một ca READY thật thay vì chỉ hiển thị ALREADY_PROCESSED.
UPDATE users
SET class_id = 'fd-class-2026-11a1', class_name = '11A1-2627',
    enrollment_date = DATE '2026-09-01', updated_at = now()
WHERE id = 'fd-student-060';

INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    student_code, created_at, updated_at, password_change_required
)
VALUES ('fd-student-061', 'demo.hs.pending', '$2a$10$F3g3JAvND2cU2O9VXo8.1OR6AlBTGi.wLWkpnJ7e4wHxUkuDOtL.a',
        'Học sinh Demo chờ phân lớp', 'demo.hs.pending@sse.local', '0922000061',
        'STUDENT', 'PENDING', 'HS270061', now(), now(), true)
ON CONFLICT (id) DO UPDATE SET status = 'PENDING', class_id = null, class_name = null, updated_at = now();

INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    created_at, updated_at, password_change_required, session_version,
    address, nationality
)
SELECT 'fd-parent-' || lpad(n::text, 3, '0'),
       'demo.ph.' || lpad(n::text, 3, '0'),
       '$2a$10$EIANrs2dAzHTvt5x957bLe2C7eVWgTiQokvlVuDMJ/1WrOKnr82Gu',
       'Phụ huynh Demo ' || lpad(n::text, 3, '0'),
       'demo.ph.' || lpad(n::text, 3, '0') || '@sse.local',
       '0933' || lpad(n::text, 6, '0'), 'PARENT', 'ACTIVE',
       now(), now(), false, 0, 'Thành phố Hà Nội', 'Việt Nam'
FROM generate_series(1, 48) n
ON CONFLICT (id) DO UPDATE SET password_hash = excluded.password_hash,
    full_name = excluded.full_name, email = excluded.email, phone = excluded.phone,
    status = 'ACTIVE', updated_at = now();

INSERT INTO users (
    id, username, password_hash, full_name, email, phone, role, status,
    created_at, updated_at, deleted_at, delete_reason, password_change_required
)
VALUES ('fd-parent-049', 'demo.ph.deleted', '$2a$10$EIANrs2dAzHTvt5x957bLe2C7eVWgTiQokvlVuDMJ/1WrOKnr82Gu',
        'Phụ huynh Demo đã xóa', 'demo.ph.deleted@sse.local', '0933000049',
        'PARENT', 'DELETED', now(), now(), now(), 'Dữ liệu kiểm thử trạng thái xóa', false)
ON CONFLICT (id) DO UPDATE SET status = 'DELETED', deleted_at = now(), updated_at = now();

INSERT INTO user_roles (id, user_id, role_id)
SELECT 'fd-ur-' || md5(u.id || ':' || r.id), u.id, r.id
FROM users u JOIN roles r ON r.code = u.role
WHERE u.id LIKE 'fd-%'
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO student_class_enrollments (
    id, academic_year_id, class_id, student_id, student_code, student_name,
    enrollment_type, status, enrolled_by, enrolled_at
)
SELECT 'fd-enrollment-' || lpad(n::text, 3, '0'), 'fd-ay-2027', u.class_id, u.id,
       u.student_code, u.full_name, 'DEMO_BASELINE', 'ACTIVE', 'fd-admin-001', now()
FROM generate_series(1, 59) n
JOIN users u ON u.id = 'fd-student-' || lpad(n::text, 3, '0')
ON CONFLICT (academic_year_id, student_id) DO UPDATE SET
    class_id = excluded.class_id, student_code = excluded.student_code,
    student_name = excluded.student_name, status = 'ACTIVE';

INSERT INTO student_class_enrollments (
    id, academic_year_id, class_id, student_id, student_code, student_name,
    enrollment_type, status, enrolled_by, enrolled_at
)
VALUES ('fd-enrollment-2026-060', 'fd-ay-2026', 'fd-class-2026-11a1',
        'fd-student-060', 'HS270060', 'Học sinh Demo 060',
        'DEMO_YEAR_END_SOURCE', 'ACTIVE', 'fd-admin-001', DATE '2026-09-01')
ON CONFLICT (academic_year_id, student_id) DO UPDATE SET
    class_id = excluded.class_id, student_code = excluded.student_code,
    student_name = excluded.student_name, enrollment_type = excluded.enrollment_type,
    status = 'ACTIVE';

INSERT INTO parent_student (id, parent_id, student_id, primary_contact)
SELECT 'fd-parent-student-' || lpad(n::text, 3, '0'),
       'fd-parent-' || lpad((CASE WHEN n <= 24 THEN ceil(n / 2.0) ELSE n - 12 END)::integer::text, 3, '0'),
       'fd-student-' || lpad(n::text, 3, '0'),
       CASE WHEN n <= 24 THEN mod(n, 2) = 1 ELSE true END
FROM generate_series(1, 60) n
ON CONFLICT (parent_id, student_id) DO UPDATE SET primary_contact = excluded.primary_contact;

-- ---------------------------------------------------------------------------
-- Teacher capabilities and two-semester teaching assignments
-- ---------------------------------------------------------------------------
WITH subject_catalog AS (
    SELECT row_number() OVER (ORDER BY display_order)::integer AS subject_no, code
    FROM (VALUES (1, 'MATH'), (2, 'LIT'), (3, 'ENG'), (4, 'PHYS'),
        (5, 'CHEM'), (6, 'BIO'), (7, 'HIST'), (8, 'GEO'),
        (9, 'CIVIC'), (10, 'PE'), (11, 'CHAOCO'), (12, 'SHL')) x(display_order, code)
)
INSERT INTO teacher_subject_capabilities (id, teacher_id, subject_id, primary_subject, active, created_at)
SELECT 'fd-cap-' || lpad(n::text, 3, '0'), 'fd-teacher-' || lpad(n::text, 3, '0'),
       s.id, true, true, now()
FROM generate_series(1, 36) n
JOIN subject_catalog c ON c.subject_no = 1 + mod(n - 1, 12)
JOIN subjects s ON s.code = c.code
ON CONFLICT (teacher_id, subject_id) DO UPDATE SET primary_subject = true, active = true;

-- Chào cờ và sinh hoạt lớp là nhiệm vụ của GVCN, không phải môn chuyên môn.
-- Khai báo capability bổ sung để service kiểm tra đúng chính giáo viên chủ nhiệm.
INSERT INTO teacher_subject_capabilities (
    id, teacher_id, subject_id, primary_subject, active, created_at
)
SELECT 'fd-cap-homeroom-'||lower(s.code)||'-'||lower(c.code),
       c.homeroom_teacher_id, s.id, false, true, now()
FROM classes c CROSS JOIN subjects s
WHERE c.academic_year_id='fd-ay-2027'
  AND s.code IN ('CHAOCO','SHL')
ON CONFLICT (teacher_id, subject_id) DO UPDATE SET active=true;

WITH class_catalog AS (
    SELECT row_number() OVER (ORDER BY grade_level, code)::integer AS class_no,
           id, code, grade_level, homeroom_teacher_id
    FROM classes WHERE academic_year_id = 'fd-ay-2027'
), subject_catalog AS (
    SELECT row_number() OVER (ORDER BY display_order)::integer AS subject_no,
           code, weekly_periods
    FROM (VALUES (1, 'MATH', 2), (2, 'LIT', 2), (3, 'ENG', 2),
        (4, 'PHYS', 2), (5, 'CHEM', 2), (6, 'BIO', 2),
        (7, 'HIST', 2), (8, 'GEO', 2), (9, 'CIVIC', 2),
        (10, 'PE', 2), (11, 'CHAOCO', 1), (12, 'SHL', 1)) x(display_order, code, weekly_periods)
)
INSERT INTO teacher_class_subjects (
    id, teacher_id, teacher_name, class_id, class_code, subject_id, subject_name,
    semester_id, status, weekly_periods, specialized_room_periods, created_at, updated_at
)
SELECT 'fd-tcs-' || lower(c.code) || '-' || lower(s.code) || '-' || sm.sequence,
       CASE WHEN s.code IN ('CHAOCO','SHL') THEN c.homeroom_teacher_id
            ELSE 'fd-teacher-' || lpad((s.subject_no + 12 *
                CASE c.grade_level WHEN 'K10' THEN 0 WHEN 'K11' THEN 1 ELSE 2 END)::text, 3, '0') END,
       teacher.full_name, c.id, c.code, subject.id, subject.name,
       sm.id, 'ACTIVE', s.weekly_periods,
       CASE WHEN s.code IN ('PHYS', 'CHEM', 'BIO', 'PE') THEN s.weekly_periods ELSE 0 END,
       now(), now()
FROM class_catalog c CROSS JOIN subject_catalog s
JOIN subjects subject ON subject.code = s.code
CROSS JOIN semesters sm
JOIN users teacher ON teacher.id =
    CASE WHEN s.code IN ('CHAOCO','SHL') THEN c.homeroom_teacher_id
         ELSE 'fd-teacher-' || lpad((s.subject_no + 12 *
             CASE c.grade_level WHEN 'K10' THEN 0 WHEN 'K11' THEN 1 ELSE 2 END)::text, 3, '0') END
WHERE sm.academic_year_id = 'fd-ay-2027'
ON CONFLICT (class_id, subject_id, semester_id) WHERE status = 'ACTIVE'
DO UPDATE SET teacher_id = excluded.teacher_id, teacher_name = excluded.teacher_name,
    class_code = excluded.class_code, subject_name = excluded.subject_name,
    weekly_periods = excluded.weekly_periods,
    specialized_room_periods = excluded.specialized_room_periods, updated_at = now();

-- Bản ghi SYSTEM đúng contract mà AutomaticTimetableService tự tạo cho hai
-- hoạt động cố định. Các bản ghi ACTIVE phía trên vẫn phục vụ kiểm tra kế hoạch.
INSERT INTO teacher_class_subjects (
    id, teacher_id, teacher_name, class_id, class_code, subject_id, subject_name,
    semester_id, status, weekly_periods, specialized_room_periods, created_at, updated_at
)
SELECT 'activity-'||activity.code||'-'||c.id||'-'||sm.id,
       c.homeroom_teacher_id,u.full_name,c.id,c.code,s.id,s.name,
       sm.id,'SYSTEM',1,0,now(),now()
FROM classes c
JOIN users u ON u.id=c.homeroom_teacher_id
CROSS JOIN semesters sm
CROSS JOIN (VALUES ('FLAG','CHAOCO'),('HOMEROOM','SHL')) activity(code,subject_code)
JOIN subjects s ON s.code=activity.subject_code
WHERE c.academic_year_id='fd-ay-2027' AND sm.academic_year_id='fd-ay-2027'
ON CONFLICT (id) DO UPDATE SET teacher_id=excluded.teacher_id,
    teacher_name=excluded.teacher_name,class_id=excluded.class_id,
    class_code=excluded.class_code,subject_id=excluded.subject_id,
    subject_name=excluded.subject_name,status='SYSTEM',updated_at=now();

INSERT INTO teacher_class_subjects (
    id, teacher_id, teacher_name, class_id, class_code, subject_id, subject_name,
    semester_id, status, weekly_periods, specialized_room_periods, created_at, updated_at
)
SELECT 'fd-prev-tcs-' || lower(s.code) || '-' || sm.sequence,
       CASE s.code WHEN 'MATH' THEN 'fd-teacher-001'
                   WHEN 'LIT' THEN 'fd-teacher-002' ELSE 'fd-teacher-003' END,
       teacher.full_name, 'fd-class-2026-11a1', '11A1-2627', s.id, s.name,
       sm.id, 'ACTIVE', 3, 0, now(), now()
FROM subjects s
CROSS JOIN semesters sm
JOIN users teacher ON teacher.id = CASE s.code
    WHEN 'MATH' THEN 'fd-teacher-001'
    WHEN 'LIT' THEN 'fd-teacher-002' ELSE 'fd-teacher-003' END
WHERE s.code IN ('MATH','LIT','ENG') AND sm.academic_year_id='fd-ay-2026'
ON CONFLICT (class_id, subject_id, semester_id) WHERE status = 'ACTIVE'
DO UPDATE SET teacher_id=excluded.teacher_id,teacher_name=excluded.teacher_name,
    class_code=excluded.class_code,subject_name=excluded.subject_name,
    weekly_periods=excluded.weekly_periods,updated_at=now();

-- ---------------------------------------------------------------------------
-- Education program, combinations, published history and an editable draft per grade
-- ---------------------------------------------------------------------------
INSERT INTO education_programs (id, code, name, start_year, description, status, created_at, updated_at)
VALUES ('fd-program-2027', 'GDPT2027-DEMO', 'Chương trình giáo dục Demo 2027-2028', 2027,
        'Chương trình đầy đủ dùng để kiểm thử Web và Mobile.', 'ACTIVE', now(), now())
ON CONFLICT (code) DO UPDATE SET name = excluded.name, description = excluded.description,
    status = 'ACTIVE', updated_at = now();

WITH subject_periods(code, annual, hk1, hk2, weekly, required) AS (
    VALUES ('MATH',70,36,34,2,true), ('LIT',70,36,34,2,true),
           ('ENG',70,36,34,2,true), ('PHYS',70,36,34,2,true),
           ('CHEM',70,36,34,2,true), ('BIO',70,36,34,2,true),
           ('HIST',70,36,34,2,true), ('GEO',70,36,34,2,true),
           ('CIVIC',70,36,34,2,true), ('PE',70,36,34,2,true),
           ('CHAOCO',35,18,17,1,true), ('SHL',35,18,17,1,true)
)
INSERT INTO education_program_subjects (
    id, program_id, grade_level, subject_id, subject_type, annual_periods,
    semester1_periods, semester2_periods, weekly_periods, required, notes
)
SELECT 'fd-eps-' || lower(g.code) || '-' || lower(p.code), 'fd-program-2027', g.code,
       s.id, s.subject_type, p.annual, p.hk1, p.hk2, p.weekly, p.required,
       CASE WHEN p.code IN ('CHAOCO','SHL')
            THEN 'Hoạt động giáo dục; không dùng làm môn kiểm tra, đánh giá.'
            ELSE 'Cấu hình Demo hợp lệ cho năm 2027-2028.' END
FROM grade_levels g CROSS JOIN subject_periods p JOIN subjects s ON s.code = p.code
WHERE g.code IN ('K10','K11','K12')
ON CONFLICT (program_id, grade_level, subject_id) DO UPDATE SET
    subject_type = excluded.subject_type, annual_periods = excluded.annual_periods,
    semester1_periods = excluded.semester1_periods, semester2_periods = excluded.semester2_periods,
    weekly_periods = excluded.weekly_periods, required = excluded.required, notes = excluded.notes;

INSERT INTO subject_combinations (
    id, code, name, academic_year_id, grade_level, expected_class_count,
    max_students, status, created_at, updated_at
)
SELECT 'fd-comb-' || lower(g.code) || '-' || lower(c.code), c.code, c.name,
       'fd-ay-2027', g.code, 1, 40, 'ACTIVE', now(), now()
FROM grade_levels g CROSS JOIN (VALUES ('KHTN','Khoa học tự nhiên'),('KHXH','Khoa học xã hội')) c(code,name)
WHERE g.code IN ('K10','K11','K12')
ON CONFLICT (academic_year_id, grade_level, code) DO UPDATE SET
    name = excluded.name, expected_class_count = 1, max_students = 40, status = 'ACTIVE';

INSERT INTO subject_combination_subjects (id, combination_id, subject_id)
SELECT 'fd-comb-sub-' || lower(c.grade_level) || '-' || lower(c.code) || '-' || lower(s.code),
       c.id, s.id
FROM subject_combinations c JOIN subjects s ON
    (c.code='KHTN' AND s.code IN ('PHYS','CHEM','BIO')) OR
    (c.code='KHXH' AND s.code IN ('HIST','GEO','CIVIC'))
WHERE c.academic_year_id='fd-ay-2027'
ON CONFLICT (combination_id, subject_id) DO NOTHING;

INSERT INTO class_subject_combinations (class_id, combination_id, assigned_at, assigned_by)
SELECT cl.id, c.id, now(), 'fd-admin-001'
FROM classes cl JOIN subject_combinations c
  ON c.academic_year_id=cl.academic_year_id AND c.grade_level=cl.grade_level
 AND c.code=CASE WHEN cl.code LIKE '%A1' THEN 'KHTN' ELSE 'KHXH' END
WHERE cl.academic_year_id='fd-ay-2027'
ON CONFLICT (class_id) DO UPDATE SET combination_id=excluded.combination_id,
    assigned_at=excluded.assigned_at, assigned_by=excluded.assigned_by;

INSERT INTO academic_training_plans (
    id, academic_year_id, grade_level, name, status, max_progress_gap_days,
    published_at, published_by, created_at, updated_at, version_number,
    program_id, description, created_by, approved_at, approved_by,
    workflow_comment, validation_snapshot, validated_at
)
SELECT 'fd-plan-' || lower(g.code) || '-v' || v.version,
       'fd-ay-2027', g.code,
       'Kế hoạch giáo dục ' || g.name || ' · phiên bản ' || v.version,
       CASE WHEN v.version=1 THEN 'ARCHIVED'
            WHEN v.version=2 THEN 'PUBLISHED' ELSE 'DRAFT' END,
       2, CASE WHEN v.version=1 THEN now() - interval '14 days'
               WHEN v.version=2 THEN now() - interval '7 days' ELSE null END,
       'fd-admin-001', now() - interval '30 days', now(), v.version,
       'fd-program-2027',
       CASE WHEN v.version=1 THEN 'Bản nền lưu lịch sử trước khi điều chỉnh.'
            WHEN v.version=2 THEN 'Bản chính thức đã công bố cho giáo viên, học sinh và phụ huynh.'
            ELSE 'Bản nháp hoàn chỉnh để Admin thử kiểm tra và công bố phiên bản mới.' END,
       'fd-admin-001',
       CASE WHEN v.version IN (1,2) THEN now() - interval '8 days' ELSE null END,
       CASE WHEN v.version IN (1,2) THEN 'fd-admin-002' ELSE null END,
       CASE WHEN v.version=3 THEN 'Bản nháp sẵn sàng kiểm tra.'
            ELSE 'Dữ liệu Demo đã kiểm tra và công bố.' END,
       null, null
FROM grade_levels g CROSS JOIN (VALUES (1),(2),(3)) v(version)
WHERE g.code IN ('K10','K11','K12')
ON CONFLICT (id) DO UPDATE SET name=excluded.name, status=excluded.status,
    published_at=excluded.published_at, published_by=excluded.published_by,
    program_id=excluded.program_id, description=excluded.description,
    approved_at=excluded.approved_at, approved_by=excluded.approved_by,
    workflow_comment=excluded.workflow_comment, validation_snapshot=null, validated_at=null,
    updated_at=now();

WITH subject_periods(code, display_order, weekly, hk1, hk2, exam_required) AS (
    VALUES ('MATH',1,2,36,34,true), ('LIT',2,2,36,34,true),
           ('ENG',3,2,36,34,true), ('PHYS',4,2,36,34,true),
           ('CHEM',5,2,36,34,true), ('BIO',6,2,36,34,true),
           ('HIST',7,2,36,34,true), ('GEO',8,2,36,34,true),
           ('CIVIC',9,2,36,34,true), ('PE',10,2,36,34,true),
           ('CHAOCO',11,1,18,17,false), ('SHL',12,1,18,17,false)
)
INSERT INTO academic_training_plan_subjects (
    id, plan_id, semester_id, subject_id, weekly_periods, total_periods,
    start_date, end_date, exam_required, display_order, created_at, updated_at
)
SELECT 'fd-plan-sub-' || lower(p.grade_level) || '-v' || p.version_number ||
       '-s' || sm.sequence || '-' || lower(cfg.code),
       p.id, sm.id, s.id, cfg.weekly,
       CASE WHEN sm.sequence=1 THEN cfg.hk1 ELSE cfg.hk2 END,
       sm.start_date, sm.end_date, cfg.exam_required, cfg.display_order, now(), now()
FROM academic_training_plans p
JOIN semesters sm ON sm.academic_year_id=p.academic_year_id
CROSS JOIN subject_periods cfg JOIN subjects s ON s.code=cfg.code
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (plan_id, semester_id, subject_id) DO UPDATE SET
    weekly_periods=excluded.weekly_periods, total_periods=excluded.total_periods,
    start_date=excluded.start_date, end_date=excluded.end_date,
    exam_required=excluded.exam_required, display_order=excluded.display_order, updated_at=now();

INSERT INTO academic_training_plan_stages (
    id, plan_subject_id, code, name, sequence, start_date, end_date,
    target_periods, description, created_at, updated_at
)
SELECT ps.id || '-stage-' || part.part,
       ps.id, 'GD' || part.part,
       CASE part.part WHEN 1 THEN 'Giai đoạn nền tảng' ELSE 'Giai đoạn vận dụng' END,
       part.part,
       CASE part.part WHEN 1 THEN ps.start_date
            ELSE ps.start_date + ((ps.end_date - ps.start_date) / 2) + 1 END,
       CASE part.part WHEN 1 THEN ps.start_date + ((ps.end_date - ps.start_date) / 2)
            ELSE ps.end_date END,
       CASE part.part WHEN 1 THEN (ps.total_periods / 2)
            ELSE ps.total_periods - (ps.total_periods / 2) END,
       'Giai đoạn được tạo tự động cho bộ Full Demo.', now(), now()
FROM academic_training_plan_subjects ps CROSS JOIN (VALUES (1),(2)) part(part)
JOIN academic_training_plans p ON p.id=ps.plan_id
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (plan_subject_id, code) DO UPDATE SET name=excluded.name,
    sequence=excluded.sequence, start_date=excluded.start_date, end_date=excluded.end_date,
    target_periods=excluded.target_periods, description=excluded.description, updated_at=now();

INSERT INTO academic_curriculum_items (
    id, plan_subject_id, parent_id, item_type, code, title, sequence,
    planned_periods, description, created_at, updated_at
)
SELECT ps.id || '-chapter', ps.id, null, 'CHAPTER', 'CH1',
       'Chương trình ' || s.name, 1, 0, 'Khung nội dung Full Demo.', now(), now()
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id JOIN subjects s ON s.id=ps.subject_id
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (plan_subject_id, code) DO UPDATE SET title=excluded.title, updated_at=now();

INSERT INTO academic_curriculum_items (
    id, plan_subject_id, parent_id, item_type, code, title, sequence,
    planned_periods, description, created_at, updated_at
)
SELECT ps.id || '-topic', ps.id, ps.id || '-chapter', 'TOPIC', 'CD1',
       'Chủ đề trọng tâm ' || s.name, 1, 0, 'Chủ đề Full Demo.', now(), now()
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id JOIN subjects s ON s.id=ps.subject_id
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (plan_subject_id, code) DO UPDATE SET title=excluded.title, parent_id=excluded.parent_id, updated_at=now();

INSERT INTO academic_curriculum_items (
    id, plan_subject_id, parent_id, item_type, code, title, sequence,
    planned_periods, description, created_at, updated_at
)
SELECT ps.id || '-lesson-' || part.part, ps.id, ps.id || '-topic', 'LESSON',
       'BH' || part.part,
       CASE part.part WHEN 1 THEN 'Kiến thức nền tảng' ELSE 'Luyện tập và vận dụng' END,
       part.part,
       CASE part.part WHEN 1 THEN ps.weekly_periods *
            CASE WHEN sm.sequence=1 THEN 9 ELSE 8 END
            ELSE ps.total_periods - ps.weekly_periods *
            CASE WHEN sm.sequence=1 THEN 9 ELSE 8 END END,
       'Bài học Full Demo có số tiết khớp kế hoạch.', now(), now()
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id JOIN semesters sm ON sm.id=ps.semester_id
CROSS JOIN (VALUES (1),(2)) part(part)
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (plan_subject_id, code) DO UPDATE SET title=excluded.title,
    parent_id=excluded.parent_id, sequence=excluded.sequence,
    planned_periods=excluded.planned_periods, updated_at=now();

INSERT INTO academic_curriculum_distributions (
    id, plan_subject_id, curriculum_item_id, week_number, content_type,
    title, periods, notes, created_at, updated_at
)
SELECT ps.id || '-week-' || lpad(week_no::text,2,'0'), ps.id,
       ps.id || '-lesson-' || CASE WHEN week_no <= CASE WHEN sm.sequence=1 THEN 9 ELSE 8 END THEN 1 ELSE 2 END,
       week_no, CASE WHEN week_no IN (9,17,18) THEN 'REVIEW' ELSE 'THEORY' END,
       'Tuần ' || week_no || ' · ' || s.name, ps.weekly_periods,
       'Phân phối chương trình Full Demo.', now(), now()
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id JOIN semesters sm ON sm.id=ps.semester_id
JOIN subjects s ON s.id=ps.subject_id
CROSS JOIN LATERAL generate_series(1, CASE WHEN sm.sequence=1 THEN 18 ELSE 17 END) week_no
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (plan_subject_id, week_number, content_type, title) DO UPDATE SET
    curriculum_item_id=excluded.curriculum_item_id, periods=excluded.periods,
    notes=excluded.notes, updated_at=now();

INSERT INTO academic_training_plan_special_weeks (
    id, plan_subject_id, week_type, week_number, name, description, created_at, updated_at
)
SELECT ps.id || '-buffer', ps.id, 'BUFFER', CASE WHEN sm.sequence=1 THEN 20 ELSE 19 END,
       'Tuần dự phòng', 'Dùng bù tiến độ hoặc xử lý thay đổi lịch.', now(), now()
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id JOIN semesters sm ON sm.id=ps.semester_id
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (plan_subject_id, week_number) DO UPDATE SET week_type='BUFFER',
    name=excluded.name, description=excluded.description, updated_at=now();

INSERT INTO academic_training_plan_special_weeks (
    id, plan_subject_id, week_type, week_number, name, description, created_at, updated_at
)
SELECT ps.id || '-exam-mid', ps.id, 'EXAM', 10,
       'Tuần kiểm tra giữa kỳ', 'Mốc kiểm tra giữa kỳ theo kế hoạch.', now(), now()
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id
WHERE p.academic_year_id='fd-ay-2027' AND ps.exam_required=true
ON CONFLICT (plan_subject_id, week_number) DO UPDATE SET week_type='EXAM',
    name=excluded.name, description=excluded.description, updated_at=now();

INSERT INTO academic_training_plan_special_weeks (
    id, plan_subject_id, week_type, week_number, name, description, created_at, updated_at
)
SELECT ps.id || '-exam-final', ps.id, 'EXAM', CASE WHEN sm.sequence=1 THEN 19 ELSE 18 END,
       'Tuần kiểm tra cuối kỳ', 'Mốc kiểm tra cuối kỳ theo kế hoạch.', now(), now()
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id JOIN semesters sm ON sm.id=ps.semester_id
WHERE p.academic_year_id='fd-ay-2027' AND ps.exam_required=true
ON CONFLICT (plan_subject_id, week_number) DO UPDATE SET week_type='EXAM',
    name=excluded.name, description=excluded.description, updated_at=now();

INSERT INTO academic_assessment_plans (
    id, plan_id, semester_id, class_id, subject_id, assessment_type,
    week_number, duration_minutes, teacher_id, notes, created_at, updated_at,
    name, assessment_form, curriculum_item_ids, result_method
)
SELECT ps.id || '-assessment-' || lower(a.kind), p.id, ps.semester_id, null,
       ps.subject_id, a.kind,
       CASE a.kind WHEN 'MIDTERM' THEN 10 ELSE CASE WHEN sm.sequence=1 THEN 19 ELSE 18 END END,
       CASE WHEN a.kind='MIDTERM' THEN 60 ELSE 90 END,
       'fd-teacher-' || lpad((subject_position.subject_no + 12 *
           CASE p.grade_level WHEN 'K10' THEN 0 WHEN 'K11' THEN 1 ELSE 2 END)::text, 3, '0'),
       'Kế hoạch kiểm tra Full Demo.', now(), now(),
       CASE a.kind WHEN 'MIDTERM' THEN 'Kiểm tra giữa kỳ ' ELSE 'Kiểm tra cuối kỳ ' END || s.name,
       CASE WHEN s.code='PE' THEN 'PRACTICAL' ELSE 'WRITTEN' END,
       CASE a.kind WHEN 'MIDTERM' THEN ps.id || '-lesson-1'
            ELSE ps.id || '-lesson-1,' || ps.id || '-lesson-2' END,
       'SCORE'
FROM academic_training_plan_subjects ps
JOIN academic_training_plans p ON p.id=ps.plan_id AND p.version_number IN (2,3)
JOIN semesters sm ON sm.id=ps.semester_id JOIN subjects s ON s.id=ps.subject_id
JOIN (VALUES (1,'MATH'),(2,'LIT'),(3,'ENG'),(4,'PHYS'),(5,'CHEM'),
             (6,'BIO'),(7,'HIST'),(8,'GEO'),(9,'CIVIC'),(10,'PE'))
     subject_position(subject_no,code) ON subject_position.code=s.code
CROSS JOIN (VALUES ('MIDTERM'),('FINAL')) a(kind)
WHERE p.academic_year_id='fd-ay-2027' AND ps.exam_required=true
ON CONFLICT (id)
DO UPDATE SET duration_minutes=excluded.duration_minutes, week_number=excluded.week_number,
    name=excluded.name,
    assessment_form=excluded.assessment_form, curriculum_item_ids=excluded.curriculum_item_ids,
    result_method=excluded.result_method, teacher_id=excluded.teacher_id, updated_at=now();

-- Mỗi mốc kiểm tra có một giáo viên chính đúng khối và một giáo viên cùng
-- chuyên môn ở khối kế cận. Bảng liên kết chuẩn hóa cho phép UI chọn nhiều
-- người phụ trách mà vẫn giữ teacher_id làm giá trị tương thích client cũ.
INSERT INTO academic_assessment_plan_teachers (
    id, assessment_plan_id, teacher_id, primary_teacher, created_at
)
SELECT 'fd-apt-primary-'||a.id, a.id, a.teacher_id, true, now()
FROM academic_assessment_plans a
JOIN academic_training_plans p ON p.id=a.plan_id
WHERE p.academic_year_id='fd-ay-2027' AND a.teacher_id IS NOT NULL
ON CONFLICT (assessment_plan_id,teacher_id) DO UPDATE SET primary_teacher=true;

INSERT INTO academic_assessment_plan_teachers (
    id, assessment_plan_id, teacher_id, primary_teacher, created_at
)
SELECT 'fd-apt-secondary-'||a.id, a.id,
       'fd-teacher-'||lpad((subject_position.subject_no + 12 *
           CASE p.grade_level WHEN 'K10' THEN 1 WHEN 'K11' THEN 2 ELSE 0 END)::text,3,'0'),
       false, now()
FROM academic_assessment_plans a
JOIN academic_training_plans p ON p.id=a.plan_id
JOIN subjects s ON s.id=a.subject_id
JOIN (VALUES (1,'MATH'),(2,'LIT'),(3,'ENG'),(4,'PHYS'),(5,'CHEM'),
             (6,'BIO'),(7,'HIST'),(8,'GEO'),(9,'CIVIC'),(10,'PE'))
     subject_position(subject_no,code) ON subject_position.code=s.code
WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (assessment_plan_id,teacher_id) DO UPDATE SET primary_teacher=false;

INSERT INTO academic_plan_approval_history (
    id, plan_id, action, from_status, to_status, actor_id, comment, created_at
)
SELECT p.id || CASE WHEN p.status='DRAFT' THEN '-history-create-version'
                    ELSE '-history-publish' END,
       p.id, CASE WHEN p.status='DRAFT' THEN 'CREATE_VERSION' ELSE 'PUBLISH' END,
       CASE WHEN p.status='DRAFT' THEN 'PUBLISHED' ELSE 'APPROVED' END, p.status,
       'fd-admin-001',
       CASE WHEN p.status='PUBLISHED' THEN 'Công bố kế hoạch Full Demo.'
            WHEN p.status='DRAFT' THEN 'Tạo bản nháp mới từ kế hoạch đã công bố.'
            ELSE 'Lưu phiên bản cũ vào lịch sử.' END,
       coalesce(p.published_at, now())
FROM academic_training_plans p WHERE p.academic_year_id='fd-ay-2027'
ON CONFLICT (id) DO UPDATE SET to_status=excluded.to_status, comment=excluded.comment;

-- ---------------------------------------------------------------------------
-- Published HK1 timetable with complete assignment coverage and no collision
-- ---------------------------------------------------------------------------
INSERT INTO timetable_schedules (
    id, academic_year_id, semester_id, scope_grade_level, name, status,
    teaching_days, first_period, last_period, max_periods_per_day,
    max_progress_gap_days, max_progress_gap_periods, max_curriculum_gap_lessons,
    solve_seconds, solver_score, hard_violation_count, warning_count,
    generation_summary, generated_at, generated_by, published_at, published_by,
    created_at, updated_at, source_plan_summary, source_plan_snapshot
)
SELECT 'fd-schedule-' || lower(g.code) || '-hk1', 'fd-ay-2027', 'fd-sem-2027-1', g.code,
       'Thời khóa biểu HK1 · ' || g.name, 'PUBLISHED', 'MON,TUE,WED,THU,FRI',
       1, 11, 5, 2, 2, 1, 10, 'FULL_DEMO_VALID', 0, 0,
       '22 tiết/tuần/lớp; ca sáng và chiều; không trùng lớp, giáo viên, phòng.',
       now() - interval '6 days', 'fd-admin-001', now() - interval '5 days',
       'fd-admin-001', now() - interval '10 days', now(),
       'Kế hoạch giáo dục ' || g.name || ' phiên bản 2',
       jsonb_build_array(jsonb_build_object(
           'planId',p.id,'programId',p.program_id,'versionNumber',p.version_number,
           'gradeLevel',p.grade_level,'status',p.status,'publishedAt',p.published_at,
           'semesterId','fd-sem-2027-1','subjects',(
               SELECT jsonb_agg(jsonb_build_object(
                   'planSubjectId',ps.id,'subjectId',ps.subject_id,
                   'weeklyPeriods',ps.weekly_periods,'totalPeriods',ps.total_periods
               ) ORDER BY ps.subject_id)
               FROM academic_training_plan_subjects ps
               WHERE ps.plan_id=p.id AND ps.semester_id='fd-sem-2027-1'
           )
       ))::text
FROM grade_levels g
JOIN academic_training_plans p ON p.id='fd-plan-'||lower(g.code)||'-v2'
WHERE g.code IN ('K10','K11','K12')
ON CONFLICT (id) DO UPDATE SET status='PUBLISHED', generation_summary=excluded.generation_summary,
    generated_at=excluded.generated_at, published_at=excluded.published_at,
    source_plan_summary=excluded.source_plan_summary, source_plan_snapshot=excluded.source_plan_snapshot,
    updated_at=now();

CREATE TEMP TABLE fd_schedule_slots ON COMMIT DROP AS
WITH class_catalog AS (
    SELECT row_number() OVER (ORDER BY grade_level, code)::integer AS class_no,
           id, code, grade_level
    FROM classes WHERE academic_year_id='fd-ay-2027' AND status='ACTIVE'
), subject_positions(code, start_position, weekly) AS (
    VALUES ('MATH',1,2),('LIT',3,2),('ENG',5,2),('PHYS',7,2),
           ('CHEM',9,2),('BIO',11,2),('HIST',13,2),('GEO',15,2),
           ('CIVIC',17,2),('PE',19,2),('CHAOCO',21,1),('SHL',22,1)
), academic_placement(code, lesson_index, day_of_week) AS (
    -- Mỗi môn học hai buổi/tuần, tránh đúng ngày nghỉ do policy của
    -- AutomaticTimetableService phân cho giáo viên theo thứ tự mã.
    -- Mỗi lớp có bốn tiết học thuật/ngày; lớp A2 xoay vị trí một ô để
    -- không trùng giáo viên với lớp A1 cùng khối.
    VALUES
      ('PHYS',1,'MON'),('CHEM',1,'MON'),('CIVIC',2,'MON'),('PE',1,'MON'),
      ('MATH',1,'TUE'),('CHEM',2,'TUE'),('BIO',1,'TUE'),('PE',2,'TUE'),
      ('MATH',2,'WED'),('LIT',1,'WED'),('BIO',2,'WED'),('HIST',1,'WED'),
      ('LIT',2,'THU'),('ENG',1,'THU'),('HIST',2,'THU'),('GEO',1,'THU'),
      ('ENG',2,'FRI'),('PHYS',2,'FRI'),('GEO',2,'FRI'),('CIVIC',1,'FRI')
), occurrences AS (
    SELECT c.class_no,c.id AS class_id,c.code AS class_code,c.grade_level,
           s.id AS subject_id,s.code AS subject_code,s.name AS subject_name,
           p.start_position + n - 1 AS slot_position,
           CASE p.code WHEN 'CHAOCO' THEN 'activity-FLAG-'||c.id||'-fd-sem-2027-1'
                       WHEN 'SHL' THEN 'activity-HOMEROOM-'||c.id||'-fd-sem-2027-1'
                       ELSE t.id END AS assignment_id,
           t.teacher_id,t.teacher_name,
           p.weekly,n AS lesson_index
    FROM class_catalog c CROSS JOIN subject_positions p
    JOIN subjects s ON s.code=p.code
    JOIN teacher_class_subjects t ON t.class_id=c.id AND t.subject_id=s.id
        AND t.semester_id='fd-sem-2027-1' AND t.status='ACTIVE'
    CROSS JOIN LATERAL generate_series(1,p.weekly) n
), day_placed AS (
    SELECT o.*,
           CASE o.subject_code
               WHEN 'CHAOCO' THEN 'MON'
               WHEN 'SHL' THEN 'FRI'
               WHEN 'MATH' THEN CASE WHEN o.grade_level='K10' AND o.lesson_index=1
                                      THEN 'MON' ELSE a.day_of_week END
               WHEN 'LIT' THEN CASE WHEN o.grade_level='K10' AND o.lesson_index=1
                                     THEN 'TUE' ELSE a.day_of_week END
               WHEN 'PE' THEN CASE WHEN o.grade_level='K10' AND o.lesson_index=1
                                    THEN 'WED' ELSE a.day_of_week END
               ELSE a.day_of_week
           END AS day_of_week
    FROM occurrences o
    LEFT JOIN academic_placement a
      ON a.code=o.subject_code AND a.lesson_index=o.lesson_index
), ordered AS (
    SELECT d.*,
           row_number() OVER (
               PARTITION BY d.class_id,d.day_of_week
               ORDER BY CASE WHEN d.subject_code IN ('CHAOCO','SHL') THEN 1 ELSE 0 END,
                        d.subject_code
           )::integer AS cell_order
    FROM day_placed d
), placed AS (
    SELECT o.*,
           CASE o.subject_code
               WHEN 'CHAOCO' THEN CASE WHEN o.grade_level IN ('K10','K11') THEN 6 ELSE 1 END
               WHEN 'SHL' THEN CASE WHEN o.grade_level IN ('K10','K11') THEN 10 ELSE 5 END
               ELSE
                   CASE WHEN o.grade_level IN ('K10','K11') THEN 6 ELSE 1 END
                   + CASE WHEN o.day_of_week='MON' THEN 1 ELSE 0 END
                   + mod(o.cell_order - 1 + CASE WHEN mod(o.class_no,2)=0 THEN 1 ELSE 0 END,4)
           END AS period_no
    FROM ordered o
)
SELECT o.*,
       CASE WHEN subject_code IN ('PHYS','CHEM','BIO')
            THEN 'fd-room-lab-' || lpad(class_no::text,2,'0')
            WHEN subject_code='PE' THEN 'fd-room-gym-' || lpad(class_no::text,2,'0')
            ELSE 'fd-room-general-' || lpad(class_no::text,2,'0') END AS room_id,
       'fd-schedule-' || lower(grade_level) || '-hk1' AS schedule_id
FROM placed o;

INSERT INTO timetable_draft_slots (
    id, schedule_id, assignment_id, class_id, subject_id, subject_name,
    teacher_id, teacher_name, room_id, room_code, day_of_week, period_no,
    start_time, end_time, semester_id, lesson_index, source, pinned,
    created_at, updated_at, required_room_type
)
SELECT 'fd-draft-' || lower(class_code) || '-' || lower(subject_code) || '-' || lesson_index,
       schedule_id, assignment_id, class_id, subject_id, subject_name,
       teacher_id, teacher_name, room_id, r.code, day_of_week, period_no,
       CASE period_no WHEN 1 THEN '07:00' WHEN 2 THEN '07:50' WHEN 3 THEN '08:45'
            WHEN 4 THEN '09:35' WHEN 5 THEN '10:25' WHEN 6 THEN '13:00'
            WHEN 7 THEN '13:50' WHEN 8 THEN '14:45' WHEN 9 THEN '15:35' ELSE '16:25' END,
       CASE period_no WHEN 1 THEN '07:45' WHEN 2 THEN '08:35' WHEN 3 THEN '09:30'
            WHEN 4 THEN '10:20' WHEN 5 THEN '11:10' WHEN 6 THEN '13:45'
            WHEN 7 THEN '14:35' WHEN 8 THEN '15:30' WHEN 9 THEN '16:20' ELSE '17:10' END,
       'fd-sem-2027-1', lesson_index,
       CASE WHEN subject_code IN ('CHAOCO','SHL') THEN 'FIXED_ACTIVITY' ELSE 'AUTO' END,
       subject_code IN ('CHAOCO','SHL'), now(), now(), s.required_room_type
FROM fd_schedule_slots f JOIN rooms r ON r.id=f.room_id JOIN subjects s ON s.id=f.subject_id
ON CONFLICT (schedule_id, assignment_id, lesson_index) DO UPDATE SET
    room_id=excluded.room_id, room_code=excluded.room_code,
    day_of_week=excluded.day_of_week, period_no=excluded.period_no,
    start_time=excluded.start_time, end_time=excluded.end_time, updated_at=now();

INSERT INTO timetable_slots (
    id, class_id, subject_id, subject_name, teacher_id, teacher_name,
    room_code, day_of_week, period_no, start_time, end_time, semester_id,
    source_schedule_id
)
SELECT 'fd-slot-' || lower(class_code) || '-' || lpad(slot_position::text,2,'0'),
       class_id, subject_id, subject_name, teacher_id, teacher_name, r.code,
       day_of_week, period_no,
       CASE period_no WHEN 1 THEN '07:00' WHEN 2 THEN '07:50' WHEN 3 THEN '08:45'
            WHEN 4 THEN '09:35' WHEN 5 THEN '10:25' WHEN 6 THEN '13:00'
            WHEN 7 THEN '13:50' WHEN 8 THEN '14:45' WHEN 9 THEN '15:35' ELSE '16:25' END,
       CASE period_no WHEN 1 THEN '07:45' WHEN 2 THEN '08:35' WHEN 3 THEN '09:30'
            WHEN 4 THEN '10:20' WHEN 5 THEN '11:10' WHEN 6 THEN '13:45'
            WHEN 7 THEN '14:35' WHEN 8 THEN '15:30' WHEN 9 THEN '16:20' ELSE '17:10' END,
       'fd-sem-2027-1', schedule_id
FROM fd_schedule_slots f JOIN rooms r ON r.id=f.room_id
ON CONFLICT (class_id, semester_id, day_of_week, period_no)
    WHERE class_id IS NOT NULL AND semester_id IS NOT NULL AND day_of_week IS NOT NULL
DO UPDATE SET subject_id=excluded.subject_id, subject_name=excluded.subject_name,
    teacher_id=excluded.teacher_id, teacher_name=excluded.teacher_name,
    room_code=excluded.room_code, start_time=excluded.start_time,
    end_time=excluded.end_time, source_schedule_id=excluded.source_schedule_id;

-- Hai lớp cùng khối hoàn thành cùng một bài trong cùng ngày để có dữ liệu
-- so sánh tiến độ thật, không vượt ngưỡng chênh lệch của nghiệp vụ.
INSERT INTO class_lesson_progress (
    id,academic_year_id,semester_id,class_id,subject_id,curriculum_item_id,
    lesson_date,planned_periods,completed_periods,status,teacher_id,notes,
    created_at,updated_at,source_plan_id,source_plan_version
)
SELECT 'fd-progress-'||lower(c.code)||'-math-01','fd-ay-2027','fd-sem-2027-1',
       c.id,'fd-sub-math',item.id,DATE '2027-09-06',3,3,'COMPLETED',
       assignment.teacher_id,'Đã hoàn thành kiến thức nền tảng đúng tiến độ.',
       now()-interval '5 days',now()-interval '5 days','fd-plan-k10-v2',2
FROM classes c
JOIN teacher_class_subjects assignment ON assignment.class_id=c.id
 AND assignment.subject_id='fd-sub-math' AND assignment.semester_id='fd-sem-2027-1'
 AND assignment.status='ACTIVE'
JOIN academic_curriculum_items item ON item.id='fd-plan-sub-k10-v2-s1-math-lesson-1'
WHERE c.id IN ('fd-class-10a1','fd-class-10a2')
ON CONFLICT (id) DO UPDATE SET completed_periods=3,status='COMPLETED',
    notes=excluded.notes,updated_at=excluded.updated_at;

-- ---------------------------------------------------------------------------
-- Attendance, grade configurations, grades and correction history
-- ---------------------------------------------------------------------------
INSERT INTO attendance_records (
    id, class_id, date, note, period_no, slot_id, status, student_id,
    subject_name, late_minutes
)
SELECT 'fd-attendance-' || lpad(n::text,3,'0'), u.class_id, DATE '2027-09-06',
       CASE mod(n-1,10)
           WHEN 1 THEN 'Đến lớp muộn do tắc đường'
           WHEN 2 THEN 'Nghỉ có phép theo xác nhận phụ huynh'
           WHEN 3 THEN 'Nghỉ chưa có đơn xác nhận'
           ELSE 'Đi học đầy đủ' END,
       slot.period_no, slot.id,
       CASE mod(n-1,10) WHEN 1 THEN 'LATE' WHEN 2 THEN 'ABSENT_EXCUSED'
            WHEN 3 THEN 'ABSENT_UNEXCUSED' ELSE 'PRESENT' END,
       u.id, slot.subject_name,
       CASE WHEN mod(n-1,10)=1 THEN 10 ELSE 0 END
FROM generate_series(1,60) n
JOIN users u ON u.id='fd-student-'||lpad(n::text,3,'0')
JOIN LATERAL (
    SELECT t.id,t.period_no,t.subject_name
    FROM timetable_slots t
    WHERE t.class_id=u.class_id AND t.semester_id='fd-sem-2027-1'
    ORDER BY CASE t.day_of_week WHEN 'MON' THEN 1 WHEN 'TUE' THEN 2 WHEN 'WED' THEN 3 WHEN 'THU' THEN 4 ELSE 5 END,
             t.period_no LIMIT 1
) slot ON true
ON CONFLICT (id) DO UPDATE SET status=excluded.status, note=excluded.note,
    late_minutes=excluded.late_minutes, slot_id=excluded.slot_id;

INSERT INTO attendance_excuse_requests (
    id,attendance_record_id,reason,requested_at,requested_by,requester_role,
    review_note,reviewed_at,reviewed_by,status,student_id
)
VALUES ('fd-excuse-approved','fd-attendance-003','Nghỉ khám bệnh có xác nhận của gia đình.',
        now()-interval '4 days','fd-parent-002','PARENT','Đã xác minh với phụ huynh.',
        now()-interval '3 days','fd-teacher-001','APPROVED','fd-student-003')
ON CONFLICT (id) DO UPDATE SET status='APPROVED',review_note=excluded.review_note,
    reviewed_at=excluded.reviewed_at,reviewed_by=excluded.reviewed_by;

INSERT INTO grade_configurations (
    id, subject_id, semester_id, category_code, category_name,
    required_count, weight, active, updated_by, updated_at
)
SELECT 'fd-grade-config-'||
       CASE sm.academic_year_id WHEN 'fd-ay-2026' THEN '26' ELSE '27' END||'-'||
       lower(s.code)||'-'||sm.sequence||'-'||lower(c.code),
       s.id,sm.id,c.code,c.name,c.required_count,c.weight,true,'fd-admin-001',now()
FROM subjects s CROSS JOIN semesters sm CROSS JOIN
    (VALUES ('ORAL','Thường xuyên',1,1.0),('15M','Định kỳ ngắn',1,1.0),
            ('MID','Giữa kỳ',1,2.0),('FINAL','Cuối kỳ',1,3.0)) c(code,name,required_count,weight)
WHERE sm.academic_year_id IN ('fd-ay-2026','fd-ay-2027')
  AND s.code IN ('MATH','LIT','ENG','PHYS','CHEM','BIO','HIST','GEO','CIVIC')
ON CONFLICT (subject_id,semester_id,category_code) DO UPDATE SET
    category_name=excluded.category_name, required_count=excluded.required_count,
    weight=excluded.weight, active=true, updated_by=excluded.updated_by, updated_at=now();

INSERT INTO grades (
    id,student_id,subject_id,subject_name,semester_id,category,category_name,
    entry_index,score,note,recorded_at
)
SELECT 'fd-grade-'||lpad(n::text,3,'0')||'-'||lower(s.code)||'-'||lower(c.code),
       u.id,s.id,s.name,'fd-sem-2027-1',c.code,c.name,1,
       CASE WHEN n=1 AND s.code='MATH' AND c.code='FINAL' THEN 9.2
            ELSE round((6.0 + mod(n+s.ord*3+c.ord*2,35)/10.0)::numeric,1)::double precision END,
       CASE WHEN n=1 AND s.code='MATH' AND c.code='FINAL'
            THEN 'Đã điều chỉnh theo biên bản phúc tra' ELSE 'Điểm Full Demo' END,
       now()-interval '3 days'
FROM generate_series(1,59) n
JOIN users u ON u.id='fd-student-'||lpad(n::text,3,'0')
CROSS JOIN (SELECT id,code,name,row_number() OVER(ORDER BY code)::integer ord
            FROM subjects WHERE code IN ('MATH','LIT','ENG')) s
CROSS JOIN (VALUES (1,'ORAL','Thường xuyên'),(2,'15M','Định kỳ ngắn'),
                   (3,'MID','Giữa kỳ'),(4,'FINAL','Cuối kỳ')) c(ord,code,name)
ON CONFLICT (student_id,subject_id,semester_id,category,entry_index) DO UPDATE SET
    score=excluded.score,note=excluded.note,recorded_at=excluded.recorded_at;

-- Dữ liệu hoàn tất của lớp nguồn 2026-2027. Chỉ ba môn có phân công nên
-- YearSummaryPreview không yêu cầu điểm cho các môn ngoài phạm vi lớp này.
INSERT INTO attendance_records (
    id, class_id, date, note, period_no, slot_id, status, student_id,
    subject_name, late_minutes
)
VALUES ('fd-prev-attendance-hk1-060','fd-class-2026-11a1',DATE '2026-10-05',
        'Chuyên cần HK1 đã hoàn tất',1,null,'PRESENT','fd-student-060','Toán',0),
       ('fd-prev-attendance-hk2-060','fd-class-2026-11a1',DATE '2027-03-06',
        'Chuyên cần HK2 đã hoàn tất',1,null,'PRESENT','fd-student-060','Toán',0)
ON CONFLICT (id) DO UPDATE SET class_id=excluded.class_id,date=excluded.date,
    status='PRESENT',student_id=excluded.student_id,note=excluded.note;

INSERT INTO grades (
    id,student_id,subject_id,subject_name,semester_id,category,category_name,
    entry_index,score,note,recorded_at
)
SELECT 'fd-prev-grade-060-s'||sm.sequence||'-'||lower(s.code)||'-'||lower(c.code),
       'fd-student-060',s.id,s.name,sm.id,c.code,c.name,1,
       round((7.2 + sm.sequence*0.3 + s.ord*0.1 + c.ord*0.1)::numeric,1)::double precision,
       'Điểm đã hoàn tất để rà soát cuối năm',sm.end_date::timestamp - interval '5 days'
FROM semesters sm
CROSS JOIN (SELECT id,code,name,row_number() OVER(ORDER BY code)::integer ord
            FROM subjects WHERE code IN ('MATH','LIT','ENG')) s
CROSS JOIN (VALUES (1,'ORAL','Thường xuyên'),(2,'15M','Định kỳ ngắn'),
                   (3,'MID','Giữa kỳ'),(4,'FINAL','Cuối kỳ')) c(ord,code,name)
WHERE sm.academic_year_id='fd-ay-2026'
ON CONFLICT (student_id,subject_id,semester_id,category,entry_index) DO UPDATE SET
    score=excluded.score,note=excluded.note,recorded_at=excluded.recorded_at;

INSERT INTO student_yearly_summaries (
    id,academic_year_id,attendance_rate,class_id,reason,result,reviewed_at,
    reviewed_by,status,student_code,student_id,student_name,updated_at,
    yearly_average,conduct_grade
)
VALUES ('fd-year-summary-2026-060','fd-ay-2026',100.0,'fd-class-2026-11a1',
        'Dữ liệu đã rà soát, chờ Admin chốt lớp.','PROMOTED',now()-interval '2 days',
        'fd-teacher-001','DRAFT','HS270060','fd-student-060','Học sinh Demo 060',
        now()-interval '2 days',8.1,'GOOD')
ON CONFLICT (academic_year_id,student_id) DO UPDATE SET
    class_id=excluded.class_id,attendance_rate=excluded.attendance_rate,
    result='PROMOTED',reviewed_at=excluded.reviewed_at,reviewed_by=excluded.reviewed_by,
    status='DRAFT',yearly_average=excluded.yearly_average,conduct_grade='GOOD',
    progression_status=null,next_class_id=null,progressed_at=null,progressed_by=null,
    updated_at=excluded.updated_at;

INSERT INTO grade_change_logs (
    id,changed_at,changed_by,grade_id,new_note,new_score,old_note,old_score,reason
)
VALUES ('fd-grade-log-001',now()-interval '2 days','fd-teacher-001',
        'fd-grade-001-math-final','Đã điều chỉnh theo biên bản phúc tra',9.2,
        'Điểm nhập lần đầu',8.4,'Đối chiếu lại bài làm và biên bản chấm')
ON CONFLICT (id) DO UPDATE SET new_score=9.2,old_score=8.4,
    reason=excluded.reason,changed_at=excluded.changed_at;

INSERT INTO homeroom_remarks (
    id,student_id,class_id,academic_year_id,semester_id,teacher_id,body,status,
    published_at,created_at,updated_at
)
VALUES ('fd-homeroom-remark-001','fd-student-001','fd-class-10a1','fd-ay-2027',
        'fd-sem-2027-1','fd-teacher-001',
        'Em chủ động học tập, cần duy trì việc đi học đúng giờ và phát huy môn Toán.',
        'PUBLISHED',now()-interval '1 day',now()-interval '2 days',now()-interval '1 day')
ON CONFLICT (student_id,semester_id) DO UPDATE SET body=excluded.body,
    status='PUBLISHED',published_at=excluded.published_at,updated_at=excluded.updated_at;

-- ---------------------------------------------------------------------------
-- Assignments and submissions. FullDemoFileSeeder attaches real MinIO objects
-- only when the reset script confirms object storage is available.
-- ---------------------------------------------------------------------------
INSERT INTO assignments (
    id,allow_late,class_id,created_at,deadline,description,status,
    subject_id,subject_name,teacher_id,teacher_name,title,reminder_count,updated_at
)
SELECT 'fd-assignment-draft',false,'fd-class-10a1',now()-interval '1 day',
       TIMESTAMPTZ '2027-09-20 23:59:00+07','Bản nháp để giáo viên tiếp tục hoàn thiện.',
       'DRAFT',s.id,s.name,t.teacher_id,t.teacher_name,
       'Bài tập nháp · Hàm số',0,now()
FROM subjects s JOIN teacher_class_subjects t ON t.subject_id=s.id
 AND t.class_id='fd-class-10a1' AND t.semester_id='fd-sem-2027-1' AND t.status='ACTIVE'
WHERE s.code='MATH'
ON CONFLICT (id) DO UPDATE SET status='DRAFT',title=excluded.title,updated_at=now();

INSERT INTO assignments (
    id,allow_late,class_id,created_at,deadline,description,status,
    subject_id,subject_name,teacher_id,teacher_name,title,reminder_count,updated_at
)
SELECT 'fd-assignment-published',true,'fd-class-10a1',now()-interval '10 days',
       TIMESTAMPTZ '2027-09-15 23:59:00+07',
       'Làm đầy đủ các câu trong file đề và nộp một file PDF.',
       'PUBLISHED',s.id,s.name,t.teacher_id,t.teacher_name,
       'Luyện tập đại số đầu năm',1,now()-interval '9 days'
FROM subjects s JOIN teacher_class_subjects t ON t.subject_id=s.id
 AND t.class_id='fd-class-10a1' AND t.semester_id='fd-sem-2027-1' AND t.status='ACTIVE'
WHERE s.code='MATH'
ON CONFLICT (id) DO UPDATE SET status='PUBLISHED',deadline=excluded.deadline,
    allow_late=true,title=excluded.title,description=excluded.description,updated_at=now();

INSERT INTO assignment_submissions (
    id,assignment_id,content,feedback,graded_at,graded_by,score,status,
    student_id,student_name,submitted_at,current_version
)
VALUES
 ('fd-submission-001','fd-assignment-published','Bài làm đầy đủ, nộp đúng hạn.',
  'Trình bày tốt, cần ghi rõ điều kiện xác định.',TIMESTAMPTZ '2027-09-14 10:00:00+07',
  'fd-teacher-001',9.2,'GRADED','fd-student-001','Học sinh Demo 001',
  TIMESTAMPTZ '2027-09-13 20:00:00+07',1),
 ('fd-submission-002','fd-assignment-published','Bài làm được nộp sau hạn.',
  null,null,null,null,'LATE','fd-student-002','Học sinh Demo 002',
  TIMESTAMPTZ '2027-09-16 08:00:00+07',1),
 ('fd-submission-003','fd-assignment-published','Bài làm đúng hạn.',
  'Đúng phương pháp; kiểm tra lại kết quả câu 4.',TIMESTAMPTZ '2027-09-14 11:00:00+07',
  'fd-teacher-001',8.4,'GRADED','fd-student-003','Học sinh Demo 003',
  TIMESTAMPTZ '2027-09-14 07:30:00+07',1)
ON CONFLICT (id) DO UPDATE SET content=excluded.content,feedback=excluded.feedback,
    graded_at=excluded.graded_at,graded_by=excluded.graded_by,score=excluded.score,
    status=excluded.status,submitted_at=excluded.submitted_at,current_version=1;

INSERT INTO assignment_submission_versions (
    id,content,submission_id,submitted_at,submitted_by,version_no
)
SELECT s.id||'-v1',s.content,s.id,s.submitted_at,s.student_id,1
FROM assignment_submissions s WHERE s.id LIKE 'fd-submission-%'
ON CONFLICT (id) DO UPDATE SET content=excluded.content,submitted_at=excluded.submitted_at;

-- ---------------------------------------------------------------------------
-- Published K10 exam schedule plus an archived version for adjustment history
-- ---------------------------------------------------------------------------
INSERT INTO exam_periods (
    id,code,name,academic_year_id,semester_id,exam_type,status,scope_grades,
    allow_subject_teacher_proctor,start_date,end_date,published_version_id,
    created_by,created_at,updated_at
)
VALUES ('fd-exam-period-hk1-k10','DEMO-HK1-K10','Kiểm tra giữa HK1 khối 10',
        'fd-ay-2027','fd-sem-2027-1','MIDTERM','PUBLISHED','K10',false,
        DATE '2027-11-08',DATE '2027-11-12',null,
        'fd-admin-001',now()-interval '20 days',now())
ON CONFLICT (academic_year_id,code) DO UPDATE SET name=excluded.name,status='PUBLISHED',
    start_date=excluded.start_date,end_date=excluded.end_date,
    published_version_id=null,updated_at=now();

INSERT INTO exam_schedule_versions (
    id,exam_period_id,version_no,status,based_on_version_id,change_reason,
    created_by,created_at,published_by,published_at,content_updated_at,
    last_validated_at,last_validation_error_count,last_validation_warning_count
)
VALUES
 ('fd-exam-version-1','fd-exam-period-hk1-k10',1,'ARCHIVED',null,
  'Bản nháp đầu tiên trước khi điều chỉnh phòng và giám thị.','fd-admin-001',
  now()-interval '18 days',null,null,now()-interval '17 days',
  now()-interval '17 days',0,1),
 ('fd-exam-version-2','fd-exam-period-hk1-k10',2,'PUBLISHED','fd-exam-version-1',
  'Đã điều chỉnh và kiểm tra xung đột.','fd-admin-001',now()-interval '16 days',
  'fd-admin-001',now()-interval '15 days',now()-interval '15 days',
  now()-interval '15 days',0,0)
ON CONFLICT (id) DO UPDATE SET status=excluded.status,change_reason=excluded.change_reason,
    published_by=excluded.published_by,published_at=excluded.published_at,
    last_validation_error_count=excluded.last_validation_error_count;

UPDATE exam_periods
SET published_version_id='fd-exam-version-2',updated_at=now()
WHERE id='fd-exam-period-hk1-k10';

WITH exam_subject(code,day_offset,duration) AS (
    VALUES ('MATH',0,90),('LIT',1,90),('ENG',2,60),('PHYS',3,60),('CHEM',4,60)
)
INSERT INTO exam_sessions (
    id,version_id,subject_id,grade_level,exam_date,start_time,duration_minutes,
    notes,created_at,updated_at,source_assessment_plan_id,source_training_plan_id,
    source_plan_version,source_plan_name,source_plan_status,source_assessment_name,
    source_assessment_type,source_assessment_form,source_assessment_week,
    source_planned_start_date,source_planned_end_date,source_synced_at,source_updated_at
)
SELECT 'fd-exam-session-'||lower(e.code),'fd-exam-version-2',s.id,'K10',
       DATE '2027-11-08'+e.day_offset,TIME '07:30',e.duration,
       'Ca thi Full Demo không xung đột.',now(),now(),a.id,'fd-plan-k10-v2',2,
       'Kế hoạch giáo dục Khối 10 · phiên bản 2','PUBLISHED',a.name,
       a.assessment_type,a.assessment_form,a.week_number,
       ps.start_date,ps.end_date,now(),now()
FROM exam_subject e JOIN subjects s ON s.code=e.code
JOIN academic_training_plan_subjects ps ON ps.plan_id='fd-plan-k10-v2'
 AND ps.semester_id='fd-sem-2027-1' AND ps.subject_id=s.id
JOIN academic_assessment_plans a ON a.plan_id='fd-plan-k10-v2'
 AND a.semester_id='fd-sem-2027-1' AND a.subject_id=s.id AND a.assessment_type='MIDTERM'
ON CONFLICT (version_id,subject_id,grade_level) DO UPDATE SET
    exam_date=excluded.exam_date,start_time=excluded.start_time,
    duration_minutes=excluded.duration_minutes,updated_at=now();

INSERT INTO exam_room_assignments (
    id,session_id,room_id,capacity_snapshot,primary_proctor_id,backup_proctor_id,
    created_at,updated_at
)
SELECT 'fd-exam-room-'||lower(s.code),es.id,'fd-room-exam-01',40,
       'fd-teacher-'||lpad((20+row_number() OVER(ORDER BY s.code))::text,3,'0'),
       'fd-teacher-'||lpad((28+row_number() OVER(ORDER BY s.code))::text,3,'0'),now(),now()
FROM exam_sessions es JOIN subjects s ON s.id=es.subject_id
WHERE es.version_id='fd-exam-version-2'
ON CONFLICT (session_id,room_id) DO UPDATE SET
    primary_proctor_id=excluded.primary_proctor_id,
    backup_proctor_id=excluded.backup_proctor_id,updated_at=now();

INSERT INTO exam_room_students (
    id,session_id,room_assignment_id,student_id,student_code,student_name,
    class_id,class_code,seat_no
)
SELECT 'fd-exam-seat-'||lower(s.code)||'-'||lpad(st.n::text,3,'0'),es.id,ra.id,
       u.id,u.student_code,u.full_name,u.class_id,u.class_name,st.n
FROM exam_sessions es JOIN subjects s ON s.id=es.subject_id
JOIN exam_room_assignments ra ON ra.session_id=es.id
CROSS JOIN generate_series(1,20) st(n)
JOIN users u ON u.id='fd-student-'||lpad(st.n::text,3,'0')
WHERE es.version_id='fd-exam-version-2'
ON CONFLICT (session_id,student_id) DO UPDATE SET room_assignment_id=excluded.room_assignment_id,
    seat_no=excluded.seat_no,student_name=excluded.student_name;

-- ---------------------------------------------------------------------------
-- Finance: class and individual fee periods, invoice states and reconciled cash
-- ---------------------------------------------------------------------------
INSERT INTO fee_periods (
    id,academic_year_id,apply_to_grades,code,created_at,due_date,name,status,
    target_type,published_at,fee_type,semester_id
)
VALUES
 ('fd-fee-class','fd-ay-2027','K10','DEMO-HP-K10-HK1',now()-interval '20 days',
  DATE '2027-09-30','Học phí HK1 lớp 10A1','PUBLISHED','CLASS',now()-interval '18 days','TUITION','fd-sem-2027-1'),
 ('fd-fee-student','fd-ay-2027',null,'DEMO-THU-RIENG-HS001',now()-interval '10 days',
  DATE '2027-10-10','Khoản thu hoạt động riêng HS001','PUBLISHED','STUDENT',now()-interval '9 days','ACTIVITY','fd-sem-2027-1')
ON CONFLICT (id) DO UPDATE SET code=excluded.code,name=excluded.name,status='PUBLISHED',
    target_type=excluded.target_type,published_at=excluded.published_at,
    fee_type=excluded.fee_type,semester_id=excluded.semester_id;

INSERT INTO fee_period_targets (id,fee_period_id,target_id,target_type)
VALUES ('fd-fee-target-class','fd-fee-class','fd-class-10a1','CLASS'),
       ('fd-fee-target-student','fd-fee-student','fd-student-001','STUDENT')
ON CONFLICT (fee_period_id,target_type,target_id) DO NOTHING;

INSERT INTO fee_period_items (id,amount,fee_period_id,grade_level,name,target_type)
VALUES ('fd-fee-item-tuition',1000000,'fd-fee-class','K10','Học phí HK1','CLASS'),
       ('fd-fee-item-insurance',200000,'fd-fee-class','K10','Bảo hiểm học sinh','CLASS'),
       ('fd-fee-item-activity',350000,'fd-fee-student',null,'Hoạt động trải nghiệm','STUDENT')
ON CONFLICT (id) DO UPDATE SET amount=excluded.amount,name=excluded.name,target_type=excluded.target_type;

INSERT INTO fee_period_item_targets (id,fee_period_item_id,target_id,target_type)
VALUES ('fd-fit-tuition','fd-fee-item-tuition','fd-class-10a1','CLASS'),
       ('fd-fit-insurance','fd-fee-item-insurance','fd-class-10a1','CLASS'),
       ('fd-fit-activity','fd-fee-item-activity','fd-student-001','STUDENT')
ON CONFLICT (fee_period_item_id,target_type,target_id) DO NOTHING;

INSERT INTO invoices (
    id,code,due_date,fee_period_id,issued_at,paid_amount,parent_id,status,
    student_id,student_name,total_amount,reminder_count
)
SELECT 'fd-invoice-'||lpad(n::text,3,'0'),'HD-DEMO-'||lpad(n::text,3,'0'),
       DATE '2027-09-30','fd-fee-class',now()-interval '17 days',
       CASE n WHEN 2 THEN 600000 WHEN 3 THEN 1200000 ELSE 0 END,
       'fd-parent-'||lpad(ceil(n/2.0)::integer::text,3,'0'),
       CASE n WHEN 2 THEN 'PARTIAL' WHEN 3 THEN 'PAID' WHEN 4 THEN 'OVERDUE'
            WHEN 5 THEN 'CANCELLED' WHEN 6 THEN 'VOID' ELSE 'PENDING' END,
       u.id,u.full_name,1200000,CASE WHEN n IN(1,4) THEN 1 ELSE 0 END
FROM generate_series(1,10) n JOIN users u ON u.id='fd-student-'||lpad(n::text,3,'0')
ON CONFLICT (id) DO UPDATE SET paid_amount=excluded.paid_amount,status=excluded.status,
    total_amount=excluded.total_amount,parent_id=excluded.parent_id,reminder_count=excluded.reminder_count;

INSERT INTO invoices (
    id,code,due_date,fee_period_id,issued_at,paid_amount,parent_id,status,
    student_id,student_name,total_amount,reminder_count
)
VALUES ('fd-invoice-private-001','HD-DEMO-RIENG-001',DATE '2027-10-10','fd-fee-student',
        now()-interval '8 days',0,'fd-parent-001','PENDING','fd-student-001',
        'Học sinh Demo 001',350000,0)
ON CONFLICT (id) DO UPDATE SET status='PENDING',paid_amount=0,total_amount=350000;

INSERT INTO invoice_items (id,amount,invoice_id,name,fee_period_item_id,source_target_type)
SELECT 'fd-invoice-item-'||lpad(n::text,3,'0')||'-tuition',1000000,
       'fd-invoice-'||lpad(n::text,3,'0'),'Học phí HK1','fd-fee-item-tuition','CLASS'
FROM generate_series(1,10) n
UNION ALL
SELECT 'fd-invoice-item-'||lpad(n::text,3,'0')||'-insurance',200000,
       'fd-invoice-'||lpad(n::text,3,'0'),'Bảo hiểm học sinh','fd-fee-item-insurance','CLASS'
FROM generate_series(1,10) n
UNION ALL
SELECT 'fd-invoice-item-private',350000,'fd-invoice-private-001',
       'Hoạt động trải nghiệm','fd-fee-item-activity','STUDENT'
ON CONFLICT (id) DO UPDATE SET amount=excluded.amount,name=excluded.name,
    fee_period_item_id=excluded.fee_period_item_id,source_target_type=excluded.source_target_type;

INSERT INTO payments (
    id,invoice_id,amount,method,status,txn_ref,note,created_at,updated_at,paid_at,
    bank_qr_url,bank_transfer_content,auto_provisioned
)
VALUES
 ('fd-payment-pending','fd-invoice-001',1200000,'MB_BANK_TRANSFER','PENDING',
  'DEMO-PENDING-001','Đang chờ đối soát; không tự đánh dấu thành công.',now()-interval '2 days',now(),null,
  'https://img.vietqr.io/image/MB-demo-compact2.png','SSE HD-DEMO-001',true),
 ('fd-payment-partial','fd-invoice-002',600000,'CASH','SUCCESS','DEMO-CASH-002',
  'Đã thu tiền mặt một phần và đối soát thủ công.',now()-interval '5 days',now()-interval '5 days',
  now()-interval '5 days',null,null,false),
 ('fd-payment-success','fd-invoice-003',1200000,'CASH','SUCCESS','DEMO-CASH-003',
  'Đã thu đủ tiền mặt và phát hành biên nhận.',now()-interval '6 days',now()-interval '6 days',
  now()-interval '6 days',null,null,false),
 ('fd-payment-failed','fd-invoice-004',1200000,'MB_BANK_TRANSFER','FAILED','DEMO-FAILED-004',
  'Ảnh biên lai không hợp lệ; phụ huynh cần nộp lại.',now()-interval '3 days',now()-interval '1 day',
  null,null,'SSE HD-DEMO-004',false)
ON CONFLICT (id) DO UPDATE SET amount=excluded.amount,status=excluded.status,
    note=excluded.note,updated_at=excluded.updated_at,paid_at=excluded.paid_at;

INSERT INTO payment_receipts (
    id,amount,generation_attempts,invoice_code,invoice_id,issued_at,issued_by,
    method,parent_id,payment_id,receipt_number,status,student_code,student_id,
    student_name,revision,file_id,generated_at,generation_error
)
VALUES
 ('fd-receipt-partial',600000,1,'HD-DEMO-002','fd-invoice-002',now()-interval '5 days',
  'fd-admin-001','CASH','fd-parent-001','fd-payment-partial','PT-DEMO-002','FAILED',
  'HS270002','fd-student-002','Học sinh Demo 002',1,null,null,
  'Chưa tạo file PDF vì dịch vụ lưu trữ chưa được bật.'),
 ('fd-receipt-success',1200000,1,'HD-DEMO-003','fd-invoice-003',now()-interval '6 days',
  'fd-admin-001','CASH','fd-parent-002','fd-payment-success','PT-DEMO-003','FAILED',
  'HS270003','fd-student-003','Học sinh Demo 003',1,null,null,
  'Chưa tạo file PDF vì dịch vụ lưu trữ chưa được bật.')
ON CONFLICT (id) DO UPDATE SET amount=excluded.amount,status='FAILED',revision=1,
    file_id=null,generated_at=null,generation_error=excluded.generation_error;

INSERT INTO payment_reconciliation_runs (
    id,discrepancy_count,gross_amount,net_amount,payment_count,reconciliation_date,
    refund_amount,refund_count,run_at,run_by,run_count,status,from_date,to_date,
    payment_method,min_amount,max_amount,scope_key
)
VALUES ('fd-reconciliation-cash',0,1800000,1800000,2,DATE '2027-09-20',0,0,
        now()-interval '4 days','fd-admin-002',1,'BALANCED',DATE '2027-09-01',
        DATE '2027-09-20','CASH',null,null,
        '2027-09-01|2027-09-20|CASH|*|*')
ON CONFLICT (scope_key) DO UPDATE SET gross_amount=1800000,net_amount=1800000,
    payment_count=2,discrepancy_count=0,status='BALANCED',run_at=excluded.run_at;

INSERT INTO payment_reconciliation_method_summaries (
    id,gross_amount,method,net_amount,payment_count,refund_amount,refund_count,run_id
)
VALUES ('fd-reconciliation-cash-summary',1800000,'CASH',1800000,2,0,0,
        'fd-reconciliation-cash')
ON CONFLICT (run_id,method) DO UPDATE SET gross_amount=1800000,net_amount=1800000,
    payment_count=2,refund_amount=0,refund_count=0;

-- ---------------------------------------------------------------------------
-- Notifications, chat, clubs, identity sessions and important audit records
-- ---------------------------------------------------------------------------
INSERT INTO announcements (id,audience,body,created_at,created_by,title)
VALUES
 ('fd-announcement-all','ALL',
  'Nhà trường chào mừng năm học 2027-2028. Vui lòng kiểm tra thời khóa biểu và thông báo mới.',
  now()-interval '12 days','fd-admin-001','Chào mừng năm học mới'),
 ('fd-announcement-student','STUDENT',
  'Học sinh khối 10 kiểm tra lịch thi giữa học kỳ đã được công bố.',
  now()-interval '8 days','fd-admin-001','Lịch kiểm tra giữa học kỳ')
ON CONFLICT (id) DO UPDATE SET audience=excluded.audience,body=excluded.body,
    title=excluded.title,created_at=excluded.created_at;

INSERT INTO notifications (
    id,body,created_at,read,recipient_id,ref_id,ref_type,title,type,channel,
    sent_at,status,attempt_count,deep_link,group_key,read_at
)
VALUES
 ('fd-noti-admin-plan','Kế hoạch giáo dục ba khối đã được công bố.',now()-interval '7 days',true,
  'fd-admin-001','fd-plan-k10-v2','education_plan','Kế hoạch giáo dục đã công bố','INFO','IN_APP',
  now()-interval '7 days','SENT',1,'/admin/academic-plans','fd-plan-published',now()-interval '6 days'),
 ('fd-noti-teacher-timetable','Thời khóa biểu HK1 đã được cập nhật.',now()-interval '5 days',false,
  'fd-teacher-001','fd-schedule-k10-hk1','timetable','Thời khóa biểu mới','TIMETABLE','IN_APP',
  now()-interval '5 days','SENT',1,'/teacher/timetable','fd-timetable',null),
 ('fd-noti-student-assignment','Môn Toán có bài tập mới, hạn nộp 15/09/2027.',now()-interval '9 days',false,
  'fd-student-001','fd-assignment-published','assignment','Bài tập mới','ASSIGNMENT','IN_APP',
  now()-interval '9 days','SENT',1,'/student/assignments','fd-assignment',null),
 ('fd-noti-student-exam','Lịch kiểm tra giữa HK1 khối 10 đã được công bố.',now()-interval '15 days',true,
  'fd-student-001','fd-exam-period-hk1-k10','exam','Lịch kiểm tra mới','EXAM','IN_APP',
  now()-interval '15 days','SENT',1,'/student/exams','fd-exam',now()-interval '14 days'),
 ('fd-noti-parent-late','Học sinh Demo 002 đi học muộn 10 phút.',now()-interval '1 day',false,
  'fd-parent-001','fd-attendance-002','attendance','Cảnh báo chuyên cần','ATTENDANCE','IN_APP',
  now()-interval '1 day','SENT',1,'/parent/attendance','fd-attendance',null),
 ('fd-noti-parent-invoice','Hóa đơn HD-DEMO-001 đang chờ thanh toán.',now()-interval '17 days',true,
  'fd-parent-001','fd-invoice-001','invoice','Hóa đơn mới','FINANCE','IN_APP',
  now()-interval '17 days','SENT',1,'/parent/finance','fd-invoice',now()-interval '16 days'),
 ('fd-noti-parent-exam','Lịch kiểm tra của Học sinh Demo 001 đã được cập nhật.',now()-interval '15 days',false,
  'fd-parent-001','fd-exam-period-hk1-k10','exam','Lịch kiểm tra của con','EXAM','IN_APP',
  now()-interval '15 days','SENT',1,'/parent/exams','fd-parent-exam',null),
 ('fd-noti-admin-payment','Khoản thu tiền mặt HD-DEMO-003 đã được đối soát.',now()-interval '6 days',false,
  'fd-admin-002','fd-payment-success','payment','Thanh toán đã đối soát','FINANCE','IN_APP',
  now()-interval '6 days','SENT',1,'/admin/finance','fd-payment',null)
ON CONFLICT (id) DO UPDATE SET body=excluded.body,read=excluded.read,
    status=excluded.status,read_at=excluded.read_at;

INSERT INTO notification_delivery_logs (
    id,attempt_no,attempted_at,notification_id,provider_response,status,channel,provider
)
SELECT n.id||'-delivery',1,n.sent_at,n.id,'Full Demo in-app delivery','SENT','IN_APP','LOCAL'
FROM notifications n WHERE n.id LIKE 'fd-noti-%'
ON CONFLICT (id) DO UPDATE SET status='SENT',provider_response=excluded.provider_response;

INSERT INTO chat_messages (
    id,body,created_at,read_flag,recipient_id,recipient_name,sender_id,sender_name
)
VALUES
 ('fd-chat-001','Chào thầy/cô, tôi muốn hỏi tình hình học tập của con.',now()-interval '2 days',true,
  'fd-teacher-001','Giáo viên Toán 1','fd-parent-001','Phụ huynh Demo 001'),
 ('fd-chat-002','Em học tập tốt; phụ huynh lưu ý nhắc em đi học đúng giờ.',now()-interval '1 day 20 hours',true,
  'fd-parent-001','Phụ huynh Demo 001','fd-teacher-001','Giáo viên Toán 1'),
 ('fd-chat-003','Cảm ơn thầy/cô. Tôi đã xem thông báo chuyên cần.',now()-interval '1 day',false,
  'fd-teacher-001','Giáo viên Toán 1','fd-parent-001','Phụ huynh Demo 001')
ON CONFLICT (id) DO UPDATE SET body=excluded.body,read_flag=excluded.read_flag,
    created_at=excluded.created_at;

INSERT INTO clubs (id,capacity,created_at,description,fee,name,schedule,status)
VALUES
 ('fd-club-free',30,now()-interval '30 days','Câu lạc bộ đọc sách miễn phí.',0,
  'Câu lạc bộ Đọc sách','Thứ Tư 16:30','OPEN'),
 ('fd-club-paid',24,now()-interval '30 days','Câu lạc bộ Robotics có phí vật tư.',450000,
  'Câu lạc bộ Robotics','Thứ Bảy 08:00','OPEN')
ON CONFLICT (id) DO UPDATE SET capacity=excluded.capacity,description=excluded.description,
    fee=excluded.fee,schedule=excluded.schedule,status='OPEN';

INSERT INTO club_registrations (
    id,club_id,club_name,registered_at,registered_by,status,student_id,student_name
)
VALUES ('fd-club-registration-001','fd-club-free','Câu lạc bộ Đọc sách',now()-interval '3 days',
        'fd-parent-001','REGISTERED','fd-student-001','Học sinh Demo 001')
ON CONFLICT (id) DO UPDATE SET status='REGISTERED',registered_at=excluded.registered_at;

INSERT INTO user_devices (
    id,active,created_at,device_name,device_token,last_seen_at,platform,user_id,
    last_ip_address,last_user_agent
)
VALUES
 ('fd-device-teacher',true,now()-interval '5 days','Chrome Demo Teacher','fd-device-token-teacher',
  now()-interval '1 hour','WEB','fd-teacher-001','127.0.0.1','FullDemo/1.0'),
 ('fd-device-student',true,now()-interval '5 days','Android Demo Student','fd-device-token-student',
  now()-interval '2 hours','ANDROID','fd-student-001','127.0.0.1','FullDemo/1.0'),
 ('fd-device-parent',true,now()-interval '5 days','iPhone Demo Parent','fd-device-token-parent',
  now()-interval '3 hours','IOS','fd-parent-001','127.0.0.1','FullDemo/1.0')
ON CONFLICT (id) DO UPDATE SET active=true,last_seen_at=excluded.last_seen_at;

INSERT INTO refresh_tokens (
    id,created_at,expires_at,ip_address,revoked_at,token_hash,user_agent,user_id,
    last_seen_at,revoked_by,revoked_reason,device_id,session_version
)
VALUES ('fd-refresh-revoked',now()-interval '10 days',now()+interval '1 day','127.0.0.1',
        now()-interval '2 days',repeat('a',64),'FullDemo/1.0','fd-student-001',
        now()-interval '2 days','fd-student-001','ROTATED','fd-device-student',0)
ON CONFLICT (id) DO UPDATE SET revoked_at=excluded.revoked_at,revoked_reason='ROTATED';

INSERT INTO login_history (
    id,created_at,failure_reason,ip_address,success,user_agent,user_id,username
)
VALUES
 ('fd-login-success',now()-interval '1 day',null,'127.0.0.1',true,'FullDemo/1.0',
  'fd-student-001','demo.hs.001'),
 ('fd-login-failed',now()-interval '2 days','BAD_PASSWORD','127.0.0.1',false,'FullDemo/1.0',
  'fd-parent-001','demo.ph.001')
ON CONFLICT (id) DO UPDATE SET success=excluded.success,failure_reason=excluded.failure_reason;

INSERT INTO audit_logs (
    id,action,actor_id,actor_name,created_at,detail,entity_id,entity_type,module,role,
    request_id,before_data,after_data
)
VALUES
 ('fd-audit-grade','GRADE_UPDATED','fd-teacher-001','Giáo viên Toán 1',now()-interval '2 days',
  'Sửa điểm theo biên bản phúc tra; lý do bắt buộc đã được lưu.','fd-grade-001-math-final',
  'grade','academic','TEACHER','fd-request-grade','{"score":8.4}'::jsonb,'{"score":9.2}'::jsonb),
 ('fd-audit-payment','PAYMENT_CONFIRMED','fd-admin-001','Quản trị Demo 01',now()-interval '6 days',
  'Xác nhận thu tiền mặt và cập nhật hóa đơn.','fd-payment-success','payment','finance','ADMIN',
  'fd-request-payment','{"status":"PENDING"}'::jsonb,'{"status":"SUCCESS","amount":1200000}'::jsonb),
 ('fd-audit-plan','PLAN_PUBLISHED','fd-admin-001','Quản trị Demo 01',now()-interval '7 days',
  'Công bố kế hoạch giáo dục cho khối 10.','fd-plan-k10-v2','education_plan','academic','ADMIN',
  'fd-request-plan','{"status":"APPROVED"}'::jsonb,'{"status":"PUBLISHED","version":2}'::jsonb)
ON CONFLICT (id) DO UPDATE SET detail=excluded.detail,before_data=excluded.before_data,
    after_data=excluded.after_data,created_at=excluded.created_at;

UPDATE classes c SET student_count=x.total
FROM (SELECT class_id,count(*)::integer total FROM student_class_enrollments
      WHERE academic_year_id='fd-ay-2027' AND status='ACTIVE' GROUP BY class_id) x
WHERE c.id=x.class_id;

COMMIT;
