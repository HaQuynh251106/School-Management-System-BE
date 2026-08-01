\set ON_ERROR_STOP on
SET client_encoding TO 'UTF8';

BEGIN;

-- Khôi phục dữ liệu danh mục chuẩn đã bị thay ký tự tiếng Việt thành dấu hỏi
-- khi seed qua pipeline PowerShell không bảo toàn UTF-8.
WITH fixed(id, name) AS (VALUES
    ('sj-math','Toán'),
    ('sj-lit','Ngữ văn'),
    ('sj-eng','Tiếng Anh'),
    ('sj-phys','Vật lý'),
    ('sj-chem','Hóa học'),
    ('sj-bio','Sinh học'),
    ('sj-hist','Lịch sử'),
    ('sj-geo','Địa lý'),
    ('sj-it','Tin học'),
    ('sj-tech','Công nghệ'),
    ('sj-pe','Giáo dục thể chất'),
    ('sj-civic','Giáo dục KT&PL')
)
UPDATE subjects s SET name=f.name FROM fixed f WHERE s.id=f.id;

WITH fixed(id, name) AS (VALUES
    ('u-teacher-1','Nguyễn Đức Minh'),
    ('u-teacher-2','Lê Văn Minh'),
    ('u-teacher-3','Trần Thu Hà'),
    ('u-teacher-4','Lê Hoàng Anh'),
    ('u-teacher-5','Nguyễn Ngọc Lan'),
    ('u-teacher-6','Phạm Quốc Bảo'),
    ('u-teacher-7','Vũ Thanh Tùng'),
    ('u-teacher-8','Hoàng Hải Yến'),
    ('u-teacher-9','Ngô Mai Phương'),
    ('u-teacher-10','Đặng Quang Huy'),
    ('u-teacher-11','Phan Văn Nam'),
    ('u-teacher-12','Đỗ Hải An')
)
UPDATE users u SET full_name=f.name FROM fixed f WHERE u.id=f.id;

UPDATE users
SET full_name='Học sinh kiểm thử '||lpad(substring(username from '[0-9]+$'),2,'0'),
    address=substring(username from '[0-9]+$')||' Đường Học Đường, Hà Nội',
    guardian_name='Phụ huynh kiểm thử '||lpad(ceil(substring(username from '[0-9]+$')::numeric/2)::text,2,'0')
WHERE username ~ '^hs\.test[0-9]+$';

UPDATE users
SET full_name='Phụ huynh kiểm thử '||lpad(substring(username from '[0-9]+$'),2,'0')
WHERE username ~ '^ph\.test[0-9]+$';

UPDATE users u SET main_subject=s.name FROM subjects s WHERE u.main_subject_id=s.id;
UPDATE rooms SET name='Phòng '||code WHERE name LIKE '%?%';
UPDATE classes c
SET name='Lớp '||c.code,
    homeroom_teacher_name=u.full_name
FROM users u
WHERE u.id=c.homeroom_teacher_id;
UPDATE users u SET class_name=c.code FROM classes c WHERE c.id=u.class_id;

-- Đồng bộ các cột tên được lưu phi chuẩn hóa từ danh mục gốc.
UPDATE curriculum_requirements x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE teaching_assignments x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE teaching_assignments x SET teacher_name=u.full_name FROM users u WHERE u.id=x.teacher_id;
UPDATE teacher_load_registrations x
SET teacher_name=u.full_name,
    note=CASE WHEN x.note LIKE '%?%' THEN 'Sẵn sàng giảng dạy' ELSE x.note END,
    review_note=CASE WHEN x.review_note LIKE '%?%' THEN 'Đã duyệt đăng ký tải dạy' ELSE x.review_note END
FROM users u WHERE u.id=x.teacher_id;

UPDATE timetable_slots x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE timetable_slots x SET teacher_name=u.full_name FROM users u WHERE u.id=x.teacher_id;
UPDATE timetable_draft_slots x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE timetable_draft_slots x SET teacher_name=u.full_name FROM users u WHERE u.id=x.teacher_id;
UPDATE timetable_plan_slots x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE timetable_plan_slots x SET teacher_name=u.full_name FROM users u WHERE u.id=x.teacher_id;
UPDATE timetable_publication_slots x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE timetable_publication_slots x SET teacher_name=u.full_name FROM users u WHERE u.id=x.teacher_id;
UPDATE timetable_plans SET name=CASE version_no
    WHEN 1 THEN 'Lịch học kỳ 1 - bản nghiệm thu'
    WHEN 2 THEN 'Khôi phục lịch học kỳ 1 - lần 2'
    ELSE 'Phiên bản thời khóa biểu '||version_no END
WHERE semester_id='sm-2026-1' AND (name LIKE '%?%' OR name NOT LIKE '%[À-ỹ]%');

UPDATE grades x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE grades SET category_name=CASE category
    WHEN 'ORAL' THEN 'Kiểm tra miệng'
    WHEN '15M' THEN 'Kiểm tra 15 phút'
    WHEN 'MID' THEN 'Giữa kỳ'
    WHEN 'FINAL' THEN 'Cuối kỳ'
    ELSE category_name END;

UPDATE assignments x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE assignments x SET teacher_name=u.full_name FROM users u WHERE u.id=x.teacher_id;
UPDATE assignments SET
    title=CASE id
      WHEN 'asg-26-1' THEN 'Ôn tập đại số'
      WHEN 'asg-26-2' THEN 'Bài văn nghị luận'
      WHEN 'asg-26-3' THEN 'Thuyết trình Tiếng Anh'
      WHEN 'asg-26-4' THEN 'Thí nghiệm hóa học'
      ELSE title END,
    description=CASE id
      WHEN 'asg-26-1' THEN 'Hoàn thành bài 1 đến bài 10'
      WHEN 'asg-26-2' THEN 'Viết bài nghị luận 600 chữ'
      WHEN 'asg-26-3' THEN 'Chuẩn bị bài thuyết trình nhóm'
      WHEN 'asg-26-4' THEN 'Nộp báo cáo thí nghiệm'
      ELSE description END
WHERE title LIKE '%?%' OR description LIKE '%?%';
UPDATE assignment_submissions x SET student_name=u.full_name FROM users u WHERE u.id=x.student_id;
UPDATE assignment_submissions SET content='Bài làm của '||student_name WHERE content LIKE '%?%';
UPDATE assignment_submissions SET feedback='Bài làm tốt, trình bày rõ ràng' WHERE feedback LIKE '%?%';

UPDATE attendance_records x SET subject_name=s.name FROM timetable_slots t JOIN subjects s ON s.id=t.subject_id WHERE t.id=x.slot_id;
UPDATE attendance_records SET note=CASE status
    WHEN 'ABSENT_EXCUSED' THEN 'Đã có đơn xin nghỉ được duyệt'
    WHEN 'ABSENT_UNEXCUSED' THEN 'Chưa xác nhận lý do nghỉ'
    WHEN 'LATE' THEN 'Đi học muộn 10 phút'
    ELSE NULL END
WHERE note LIKE '%?%';

UPDATE exam_schedules x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE exam_schedules SET notes=CASE subject_id
    WHEN 'sj-eng' THEN 'Có phần nghe; thí sinh có mặt trước 15 phút'
    ELSE 'Thí sinh có mặt trước 15 phút' END
WHERE notes LIKE '%?%';
UPDATE exam_candidates x SET student_name=u.full_name FROM users u WHERE u.id=x.student_id;
UPDATE exam_rooms x SET proctor_one_name=u.full_name FROM users u WHERE u.id=x.proctor_one_id;
UPDATE exam_rooms x SET proctor_two_name=u.full_name FROM users u WHERE u.id=x.proctor_two_id;
UPDATE exam_grading_assignments x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE exam_grading_assignments x SET teacher_name=u.full_name FROM users u WHERE u.id=x.teacher_id;
UPDATE exam_results SET note='Điểm đã được giáo viên bộ môn xác nhận' WHERE note LIKE '%?%';
UPDATE exam_review_requests x SET student_name=u.full_name FROM users u WHERE u.id=x.student_id;
UPDATE exam_review_requests x SET subject_name=s.name FROM subjects s WHERE s.id=x.subject_id;
UPDATE exam_review_requests SET reason='Đề nghị kiểm tra lại phần tự luận' WHERE reason LIKE '%?%';
UPDATE exam_periods SET name='Kiểm tra giữa học kỳ 1' WHERE id='exam-mid-26';

UPDATE invoices x SET student_name=u.full_name FROM users u WHERE u.id=x.student_id;
UPDATE invoice_items SET name='Học phí và cơ sở vật chất' WHERE name LIKE '%?%';
UPDATE fee_periods SET name=CASE id
    WHEN 'fee-26-1' THEN 'Học phí học kỳ 1'
    WHEN 'fee-26-2' THEN 'Bảo hiểm y tế năm học 2026-2027'
    ELSE name END
WHERE name LIKE '%?%';
UPDATE fee_period_items SET name=CASE id
    WHEN 'fi-26-1' THEN 'Học phí học kỳ 1'
    WHEN 'fi-26-2' THEN 'Phí cơ sở vật chất'
    WHEN 'fi-26-3' THEN 'Bảo hiểm y tế'
    ELSE name END
WHERE name LIKE '%?%';
UPDATE payments SET payer_name='Phụ huynh học sinh', note='Thanh toán học phí qua VietQR'
WHERE payer_name LIKE '%?%' OR note LIKE '%?%';

UPDATE leave_requests x SET student_name=u.full_name FROM users u WHERE u.id=x.student_id;
UPDATE leave_requests x SET parent_name=u.full_name FROM users u WHERE u.id=x.parent_id;
UPDATE leave_requests x SET homeroom_teacher_name=u.full_name FROM users u WHERE u.id=x.homeroom_teacher_id;
UPDATE leave_requests SET reason=CASE id
    WHEN 'leave-26-1' THEN 'Khám bệnh theo lịch'
    WHEN 'leave-26-2' THEN 'Việc gia đình'
    WHEN 'leave-26-3' THEN 'Đi du lịch'
    ELSE reason END,
    decision_note=CASE id
    WHEN 'leave-26-1' THEN 'Đồng ý nghỉ có phép'
    WHEN 'leave-26-3' THEN 'Không đủ căn cứ xác nhận'
    ELSE decision_note END
WHERE reason LIKE '%?%' OR decision_note LIKE '%?%';

UPDATE student_yearly_summaries x SET student_name=u.full_name FROM users u WHERE u.id=x.student_id;
UPDATE student_yearly_summaries SET conduct_note='Đánh giá rèn luyện cuối năm' WHERE conduct_note LIKE '%?%';
UPDATE academic_documents x SET student_name=u.full_name FROM users u WHERE u.id=x.student_id;

UPDATE announcements SET
    title=CASE id
      WHEN 'an-26-start' THEN 'Kế hoạch khai giảng năm học 2026-2027'
      WHEN 'an-26-meeting' THEN 'Lịch họp phụ huynh đầu năm'
      ELSE title END,
    body=CASE id
      WHEN 'an-26-start' THEN 'Khai giảng lúc 07:00 ngày 05/09/2026 tại sân trường.'
      WHEN 'an-26-meeting' THEN 'Họp phụ huynh theo lớp vào sáng thứ Bảy tuần đầu tháng 9.'
      ELSE body END
WHERE title LIKE '%?%' OR body LIKE '%?%';
UPDATE notifications SET
    title=CASE id
      WHEN 'noti-26-1' THEN 'Nhắc điểm danh'
      WHEN 'noti-26-2' THEN 'Có điểm mới'
      WHEN 'noti-26-3' THEN 'Thông tin chuyên cần'
      ELSE title END,
    body=CASE id
      WHEN 'noti-26-1' THEN 'Tiết Toán lớp 10A1 sắp bắt đầu.'
      WHEN 'noti-26-2' THEN 'Điểm giữa kỳ môn Toán đã được cập nhật.'
      WHEN 'noti-26-3' THEN 'Học sinh có cập nhật trạng thái chuyên cần mới.'
      ELSE body END
WHERE title LIKE '%?%' OR body LIKE '%?%';
UPDATE chat_messages x SET sender_name=u.full_name FROM users u WHERE u.id=x.sender_id;
UPDATE chat_messages x SET recipient_name=u.full_name FROM users u WHERE u.id=x.recipient_id;
UPDATE chat_messages SET body=CASE id
    WHEN 'chat-26-1' THEN 'Thầy cho tôi hỏi tình hình học tập của cháu An.'
    WHEN 'chat-26-2' THEN 'Em An học tập ổn định, gia đình tiếp tục nhắc em hoàn thành bài tập.'
    WHEN 'chat-26-3' THEN 'Bạn gửi mình nội dung bài tập Toán nhé.'
    ELSE body END
WHERE body LIKE '%?%';

UPDATE audit_logs x SET actor_name=u.full_name FROM users u WHERE u.id=x.actor_id;
UPDATE audit_logs SET detail=CASE id
    WHEN 'audit-26-1' THEN 'Khởi tạo niên khóa 2026-2027'
    WHEN 'audit-26-2' THEN 'Tạo tự động 180 tiết thời khóa biểu'
    WHEN 'audit-26-3' THEN 'Cập nhật điểm giữa kỳ môn Toán'
    WHEN 'audit-26-4' THEN 'Phát hành đợt thu học kỳ 1'
    ELSE detail END
WHERE detail LIKE '%?%';

UPDATE school_holidays SET name=CASE id
    WHEN 'holiday-2026-09-02' THEN 'Quốc khánh'
    WHEN 'holiday-2027-01-01' THEN 'Tết Dương lịch'
    ELSE name END,
    description='Toàn trường nghỉ học'
WHERE name LIKE '%?%' OR description LIKE '%?%';

-- Đồng bộ trạng thái thời gian. Năm/học kỳ tương lai phải là PLANNED;
-- giai đoạn chứa ngày hiện tại là ACTIVE, giai đoạn đã qua là CLOSED.
UPDATE academic_years
SET status=CASE
    WHEN CURRENT_DATE < start_date THEN 'PLANNED'
    WHEN CURRENT_DATE > end_date THEN 'CLOSED'
    ELSE 'ACTIVE' END;

UPDATE semesters
SET status=CASE
    WHEN CURRENT_DATE < start_date THEN 'PLANNED'
    WHEN CURRENT_DATE > end_date THEN 'CLOSED'
    ELSE 'ACTIVE' END;

COMMIT;
