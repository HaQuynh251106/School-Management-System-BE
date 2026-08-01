\set ON_ERROR_STOP on
SET client_encoding TO 'UTF8';

DO $$
DECLARE
    actual bigint;
    role_name text;
    semester_key text;
BEGIN
    FOREACH role_name IN ARRAY ARRAY['ADMIN','ACADEMIC_STAFF','ACCOUNTANT','TEACHER','STUDENT','PARENT']
    LOOP
        SELECT count(*) INTO actual FROM users WHERE role=role_name AND status='ACTIVE';
        IF actual < 1 THEN
            RAISE EXCEPTION 'Thiếu tài khoản hoạt động cho vai trò %', role_name;
        END IF;
    END LOOP;

    SELECT count(*) INTO actual FROM academic_years WHERE id='ay-2026' AND status='PLANNED';
    IF actual <> 1 THEN RAISE EXCEPTION 'Trạng thái năm học ay-2026 không hợp lệ'; END IF;

    SELECT count(*) INTO actual FROM semesters WHERE academic_year_id='ay-2026' AND status='PLANNED';
    IF actual <> 2 THEN RAISE EXCEPTION 'Năm học ay-2026 phải có đúng 2 học kỳ sắp diễn ra'; END IF;

    FOREACH semester_key IN ARRAY ARRAY['sm-2026-1','sm-2026-2']
    LOOP
        SELECT count(*) INTO actual
        FROM (
            SELECT class_id
            FROM teaching_assignments
            WHERE semester_id=semester_key
            GROUP BY class_id
            HAVING count(DISTINCT subject_id)=12 AND sum(weekly_periods)=30
        ) complete_classes;
        IF actual <> 6 THEN
            RAISE EXCEPTION 'Học kỳ % không có đủ chương trình 12 môn/30 tiết cho 6 lớp', semester_key;
        END IF;

        SELECT count(*) INTO actual FROM timetable_slots WHERE semester_id=semester_key;
        IF actual <> 180 THEN RAISE EXCEPTION 'Học kỳ % phải có 180 tiết, hiện có %', semester_key, actual; END IF;

        SELECT count(*) INTO actual FROM timetable_plans WHERE semester_id=semester_key AND status='PUBLISHED';
        IF actual <> 1 THEN RAISE EXCEPTION 'Học kỳ % phải có đúng 1 phiên bản đã phát hành', semester_key; END IF;
    END LOOP;

    SELECT count(*) INTO actual
    FROM (
        SELECT student_id, semester_id
        FROM grades
        GROUP BY student_id, semester_id
        HAVING count(DISTINCT subject_id)=12
    ) complete_gradebooks;
    IF actual <> 60 THEN RAISE EXCEPTION 'Phải có 60 sổ điểm học kỳ hoàn chỉnh, hiện có %', actual; END IF;

    SELECT count(*) INTO actual
    FROM student_yearly_summaries
    WHERE academic_year_id='ay-2026'
      AND semester_one_average IS NOT NULL
      AND semester_two_average IS NOT NULL
      AND average_score IS NOT NULL
      AND conduct_grade IS NOT NULL
      AND promotion_status='READY';
    IF actual <> 30 THEN RAISE EXCEPTION 'Phải có 30 tổng kết đủ điều kiện, hiện có %', actual; END IF;

    SELECT count(*) INTO actual FROM users WHERE id LIKE 'u-intake-%' AND role='STUDENT' AND class_id IS NULL;
    IF actual <> 18 THEN RAISE EXCEPTION 'Phải có 18 học sinh đầu cấp chưa phân lớp, hiện có %', actual; END IF;

    SELECT count(*) INTO actual
    FROM users student
    WHERE student.role='STUDENT'
      AND NOT EXISTS (SELECT 1 FROM parent_student link WHERE link.student_id=student.id);
    IF actual <> 0 THEN RAISE EXCEPTION 'Có % học sinh chưa liên kết phụ huynh', actual; END IF;

    SELECT count(*) INTO actual FROM assignments;
    IF actual < 1 THEN RAISE EXCEPTION 'Thiếu dữ liệu bài tập'; END IF;
    SELECT count(*) INTO actual FROM attendance_records;
    IF actual < 1 THEN RAISE EXCEPTION 'Thiếu dữ liệu điểm danh'; END IF;
    SELECT count(*) INTO actual FROM exam_periods;
    IF actual < 1 THEN RAISE EXCEPTION 'Thiếu dữ liệu khảo thí'; END IF;
    SELECT count(*) INTO actual FROM invoices;
    IF actual < 1 THEN RAISE EXCEPTION 'Thiếu dữ liệu hóa đơn'; END IF;
    SELECT count(*) INTO actual FROM payments WHERE method='VIETQR' AND status='SUCCESS';
    IF actual < 1 THEN RAISE EXCEPTION 'Thiếu dữ liệu thanh toán VietQR thành công'; END IF;

    RAISE NOTICE 'UAT DATA VERIFIED: 6 roles, 2 terms, 12 subjects, 360 timetable slots, 2880 grades, 30 summaries.';
END $$;
