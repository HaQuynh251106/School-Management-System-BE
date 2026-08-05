BEGIN;

-- Dữ liệu nền cho niên khóa 2026-2027.
INSERT INTO rooms (id, code, name, capacity, supports_morning, supports_afternoon, status) VALUES
('rm-a101','A101','Phòng A101',45,true,true,'ACTIVE'),
('rm-a102','A102','Phòng A102',45,true,true,'ACTIVE'),
('rm-a103','A103','Phòng A103',45,true,true,'ACTIVE'),
('rm-a104','A104','Phòng A104',45,true,true,'ACTIVE'),
('rm-a105','A105','Phòng A105',45,true,true,'ACTIVE'),
('rm-a106','A106','Phòng A106',45,true,true,'ACTIVE'),
('rm-exam','P301','Phòng thi P301',40,true,true,'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO subjects (id, code, name, coefficient, status) VALUES
('sj-chem','CHEM','Hóa học',1,'ACTIVE'),
('sj-hist','HIST','Lịch sử',1,'ACTIVE'),
('sj-geo','GEO','Địa lý',1,'ACTIVE'),
('sj-civic','CIVIC','Giáo dục KT&PL',1,'ACTIVE'),
('sj-it','IT','Tin học',1,'ACTIVE'),
('sj-tech','TECH','Công nghệ',1,'ACTIVE'),
('sj-pe','PE','Giáo dục thể chất',1,'ACTIVE')
ON CONFLICT DO NOTHING;

-- Giáo viên bổ sung dùng mật khẩu teacher@123; bốn tài khoản chính dùng mật khẩu riêng đã công bố.
INSERT INTO users (id,username,password_hash,full_name,email,phone,role,status,created_at,teacher_code,main_subject,main_subject_id)
SELECT v.id,v.username,t.password_hash,v.full_name,v.email,v.phone,'TEACHER','ACTIVE',now(),v.code,v.subject_name,v.subject_id
FROM (VALUES
('u-teacher-3','gv.tranthuha','Trần Thu Hà','gv.tranthuha@sse.edu.vn','0901000003','GV003','Ngữ văn','sj-lit'),
('u-teacher-4','gv.leanh','Lê Hoàng Anh','gv.leanh@sse.edu.vn','0901000004','GV004','Tiếng Anh','sj-eng'),
('u-teacher-5','gv.ngoclan','Nguyễn Ngọc Lan','gv.ngoclan@sse.edu.vn','0901000005','GV005','Hóa học','sj-chem'),
('u-teacher-6','gv.quocbao','Phạm Quốc Bảo','gv.quocbao@sse.edu.vn','0901000006','GV006','Sinh học','sj-bio'),
('u-teacher-7','gv.thanhtung','Vũ Thanh Tùng','gv.thanhtung@sse.edu.vn','0901000007','GV007','Lịch sử','sj-hist'),
('u-teacher-8','gv.haiyen','Hoàng Hải Yến','gv.haiyen@sse.edu.vn','0901000008','GV008','Địa lý','sj-geo'),
('u-teacher-9','gv.maiphuong','Ngô Mai Phương','gv.maiphuong@sse.edu.vn','0901000009','GV009','Tin học','sj-it'),
('u-teacher-10','gv.quanghuy','Đặng Quang Huy','gv.quanghuy@sse.edu.vn','0901000010','GV010','Công nghệ','sj-tech'),
('u-teacher-11','gv.vannam','Phan Văn Nam','gv.vannam@sse.edu.vn','0901000011','GV011','Giáo dục thể chất','sj-pe'),
('u-teacher-12','gv.haian','Đỗ Hải An','gv.haian@sse.edu.vn','0901000012','GV012','Giáo dục KT&PL','sj-civic')
) AS v(id,username,full_name,email,phone,code,subject_name,subject_id)
CROSS JOIN (SELECT password_hash FROM users WHERE id='u-teacher-2') t
ON CONFLICT DO NOTHING;

UPDATE users SET main_subject='Toán', main_subject_id='sj-math' WHERE id='u-teacher-1';
UPDATE users SET main_subject='Vật lý', main_subject_id='sj-phys' WHERE id='u-teacher-2';

UPDATE classes SET room_id='rm-a101',room_code='A101',study_shift='MORNING',student_count=5,planned_student_count=5 WHERE id='c-10a1';
UPDATE classes SET room_id='rm-a102',room_code='A102',study_shift='AFTERNOON',student_count=5,planned_student_count=5 WHERE id='c-10a2';
INSERT INTO classes (id,academic_year_id,code,name,grade_level,homeroom_teacher_id,homeroom_teacher_name,homeroom_assigned_at,homeroom_assigned_by,student_count,capacity,study_shift,room_id,room_code,planned_student_count,auto_generated,status) VALUES
('c-11a1','ay-2026','11A1','Lớp 11A1','K11','u-teacher-3','Trần Thu Hà',now(),'u-admin-1',5,45,'MORNING','rm-a103','A103',5,true,'ACTIVE'),
('c-11a2','ay-2026','11A2','Lớp 11A2','K11','u-teacher-4','Lê Hoàng Anh',now(),'u-admin-1',5,45,'AFTERNOON','rm-a104','A104',5,true,'ACTIVE'),
('c-12a1','ay-2026','12A1','Lớp 12A1','K12','u-teacher-5','Nguyễn Ngọc Lan',now(),'u-admin-1',5,45,'MORNING','rm-a105','A105',5,true,'ACTIVE'),
('c-12a2','ay-2026','12A2','Lớp 12A2','K12','u-teacher-6','Phạm Quốc Bảo',now(),'u-admin-1',5,45,'AFTERNOON','rm-a106','A106',5,true,'ACTIVE')
ON CONFLICT DO NOTHING;

-- 29 học sinh bổ sung, tổng cộng 30 học sinh ở sáu lớp THPT.
WITH student_plan AS (
  SELECT n,
    CASE WHEN n<=4 THEN 'c-10a1' WHEN n<=9 THEN 'c-10a2' WHEN n<=14 THEN 'c-11a1'
         WHEN n<=19 THEN 'c-11a2' WHEN n<=24 THEN 'c-12a1' ELSE 'c-12a2' END class_id,
    CASE WHEN n<=4 THEN '10A1' WHEN n<=9 THEN '10A2' WHEN n<=14 THEN '11A1'
         WHEN n<=19 THEN '11A2' WHEN n<=24 THEN '12A1' ELSE '12A2' END class_code
  FROM generate_series(1,29) n
)
INSERT INTO users (id,username,password_hash,full_name,email,phone,role,status,created_at,student_code,class_id,class_name,date_of_birth,gender,address,enrollment_date,guardian_name,guardian_phone)
SELECT 'u-student-'||(100+n),'hs.test'||lpad(n::text,2,'0'),h.password_hash,'Học sinh kiểm thử '||lpad(n::text,2,'0'),
       'hs.test'||lpad(n::text,2,'0')||'@sse.edu.vn','0912'||lpad(n::text,6,'0'),'STUDENT','ACTIVE',now(),
       'HS26'||lpad(n::text,4,'0'),class_id,class_code,date '2010-01-01'+(n*17),CASE WHEN n%2=0 THEN 'FEMALE' ELSE 'MALE' END,
       n||' Đường Học Đường, Hà Nội',date '2026-08-17','Phụ huynh kiểm thử '||ceil(n/2.0)::int,'0988'||lpad(ceil(n/2.0)::int::text,6,'0')
FROM student_plan CROSS JOIN (SELECT password_hash FROM users WHERE id='u-student-2') h
ON CONFLICT DO NOTHING;

-- Hồ sơ học sinh mới đầu cấp chưa xếp lớp, dùng để kiểm tra quy trình phân lớp tự động.
INSERT INTO users (id,username,password_hash,full_name,email,phone,role,status,created_at,student_code,class_id,class_name,date_of_birth,gender,address,enrollment_date,guardian_name,guardian_phone)
SELECT 'u-intake-'||n,'hs.daucap'||lpad(n::text,2,'0'),h.password_hash,
       (ARRAY['Nguyễn Gia Hân','Trần Minh Khang','Lê Bảo Ngọc','Phạm Đức Anh','Hoàng Khánh Linh','Vũ Quốc Huy','Đỗ Minh Thư','Bùi Anh Tuấn','Ngô Hải Yến','Dương Thành Nam','Đặng Ngọc Mai','Hồ Nhật Minh','Phan Hà My','Trịnh Quang Anh','Đinh Thanh Trúc','Mai Đức Long','Lý Bảo Châu','Tạ Minh Quân'])[n],
       'hs.daucap'||lpad(n::text,2,'0')||'@sse.edu.vn','0926'||lpad(n::text,6,'0'),'STUDENT','ACTIVE',now(),
       'TS26'||lpad(n::text,4,'0'),NULL,NULL,date '2011-01-01'+(n*13),CASE WHEN n%2=0 THEN 'MALE' ELSE 'FEMALE' END,
       n||' Đường Tân Học, Hà Nội',date '2026-07-20','Phụ huynh đầu cấp '||lpad(n::text,2,'0'),'0976'||lpad(n::text,6,'0')
FROM generate_series(1,18) n CROSS JOIN (SELECT password_hash FROM users WHERE id='u-student-1') h
ON CONFLICT DO NOTHING;

INSERT INTO users (id,username,password_hash,full_name,email,phone,role,status,created_at)
SELECT 'u-parent-'||(100+n),'ph.test'||lpad(n::text,2,'0'),h.password_hash,'Phụ huynh kiểm thử '||lpad(n::text,2,'0'),
       'ph.test'||lpad(n::text,2,'0')||'@gmail.com','0988'||lpad(n::text,6,'0'),'PARENT','ACTIVE',now()
FROM generate_series(1,15) n CROSS JOIN (SELECT password_hash FROM users WHERE id='u-parent-1') h
ON CONFLICT DO NOTHING;

WITH ordered_students AS (
  SELECT id,row_number() OVER(ORDER BY id) rn FROM users WHERE role='STUDENT' AND class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')
)
INSERT INTO parent_student (id,parent_id,student_id,primary_contact)
SELECT 'ps-test-'||rn,'u-parent-'||(100+ceil(rn/2.0)::int),id,true FROM ordered_students
ON CONFLICT DO NOTHING;

INSERT INTO class_enrollments (id,student_id,class_id,academic_year_id,status,enrolled_at)
SELECT 'enr-2026-'||id,id,class_id,'ay-2026','ACTIVE',now() FROM users
WHERE role='STUDENT' AND class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')
ON CONFLICT DO NOTHING;

-- Chương trình 12 môn, đăng ký tải và phân công cho sáu lớp.
WITH curriculum(subject_id,subject_name,weekly_periods,teacher_id) AS (VALUES
('sj-math','Toán',4,'u-teacher-1'),('sj-lit','Ngữ văn',4,'u-teacher-3'),('sj-eng','Tiếng Anh',3,'u-teacher-4'),
('sj-phys','Vật lý',2,'u-teacher-2'),('sj-chem','Hóa học',2,'u-teacher-5'),('sj-bio','Sinh học',2,'u-teacher-6'),
('sj-hist','Lịch sử',1,'u-teacher-7'),('sj-geo','Địa lý',1,'u-teacher-8'),('sj-it','Tin học',2,'u-teacher-9'),
('sj-tech','Công nghệ',2,'u-teacher-10'),('sj-pe','Giáo dục thể chất',2,'u-teacher-11'),('sj-civic','Giáo dục KT&PL',2,'u-teacher-12'))
INSERT INTO curriculum_requirements (id,semester_id,grade_level,subject_id,subject_name,weekly_periods,created_at,updated_at)
SELECT 'cur-'||grade||'-'||subject_id,'sm-2026-1',grade,subject_id,subject_name,weekly_periods,now(),now()
FROM curriculum CROSS JOIN (VALUES('K10'),('K11'),('K12')) g(grade)
ON CONFLICT DO NOTHING;

INSERT INTO teacher_load_registrations (id,teacher_id,teacher_name,semester_id,max_weekly_periods,preferred_grade_levels,status,submitted_at,reviewed_at,reviewed_by,created_at,updated_at,note,review_note)
SELECT 'load-'||u.id,u.id,u.full_name,'sm-2026-1',30,'K10,K11,K12','APPROVED',now()-interval '10 days',now()-interval '9 days','u-admin-1',now(),now(),'Sẵn sàng giảng dạy','Đã duyệt tự động'
FROM users u WHERE u.role='TEACHER' AND u.id IN ('u-teacher-1','u-teacher-2','u-teacher-3','u-teacher-4','u-teacher-5','u-teacher-6','u-teacher-7','u-teacher-8','u-teacher-9','u-teacher-10','u-teacher-11','u-teacher-12')
ON CONFLICT DO NOTHING;

WITH curriculum(subject_id,subject_name,weekly_periods,teacher_id) AS (VALUES
('sj-math','Toán',4,'u-teacher-1'),('sj-lit','Ngữ văn',4,'u-teacher-3'),('sj-eng','Tiếng Anh',3,'u-teacher-4'),('sj-phys','Vật lý',2,'u-teacher-2'),
('sj-chem','Hóa học',2,'u-teacher-5'),('sj-bio','Sinh học',2,'u-teacher-6'),('sj-hist','Lịch sử',1,'u-teacher-7'),('sj-geo','Địa lý',1,'u-teacher-8'),
('sj-it','Tin học',2,'u-teacher-9'),('sj-tech','Công nghệ',2,'u-teacher-10'),('sj-pe','Giáo dục thể chất',2,'u-teacher-11'),('sj-civic','Giáo dục KT&PL',2,'u-teacher-12'))
INSERT INTO teaching_assignments (id,class_id,class_code,semester_id,subject_id,subject_name,teacher_id,teacher_name,weekly_periods,assigned_at,assigned_by,updated_at,effective_from,effective_to,status,version)
SELECT 'ta-'||c.code||'-'||x.subject_id,c.id,c.code,'sm-2026-1',x.subject_id,x.subject_name,x.teacher_id,u.full_name,x.weekly_periods,now(),'u-admin-1',now(),date '2026-08-17',date '2027-01-15','ACTIVE',0
FROM classes c CROSS JOIN curriculum x JOIN users u ON u.id=x.teacher_id
WHERE c.id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')
ON CONFLICT DO NOTHING;

-- Thời khóa biểu 25 tiết/lớp, Thứ 2-Thứ 6, năm tiết liền mạch/ngày.
DELETE FROM timetable_slots WHERE class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2');
WITH class_list AS (
  SELECT * FROM (VALUES ('c-10a1','10A1',0),('c-10a2','10A2',5),('c-11a1','11A1',10),('c-11a2','11A2',15),('c-12a1','12A1',20),('c-12a2','12A2',25)) v(class_id,class_code,offset_no)
), expanded AS (
  SELECT * FROM (VALUES
  (1,'sj-math','Toán','u-teacher-1',4),(2,'sj-lit','Ngữ văn','u-teacher-3',4),(3,'sj-eng','Tiếng Anh','u-teacher-4',3),(4,'sj-phys','Vật lý','u-teacher-2',2),
  (5,'sj-chem','Hóa học','u-teacher-5',2),(6,'sj-bio','Sinh học','u-teacher-6',2),(7,'sj-hist','Lịch sử','u-teacher-7',1),(8,'sj-geo','Địa lý','u-teacher-8',1),
  (9,'sj-it','Tin học','u-teacher-9',2),(10,'sj-tech','Công nghệ','u-teacher-10',2),(11,'sj-pe','Giáo dục thể chất','u-teacher-11',2),(12,'sj-civic','Giáo dục KT&PL','u-teacher-12',2)) x(ord,subject_id,subject_name,teacher_id,cnt)
  CROSS JOIN LATERAL generate_series(1,x.cnt) g(n)
), numbered AS (
  SELECT e.*,row_number() OVER(ORDER BY ord,n)-1 slot_no FROM expanded e
)
INSERT INTO timetable_slots (id,class_id,semester_id,subject_id,subject_name,teacher_id,teacher_name,room_code,day_of_week,period_no,start_time,end_time,locked)
SELECT 'tt26-'||cl.class_code||'-'||(n.slot_no+1),cl.class_id,'sm-2026-1',n.subject_id,n.subject_name,n.teacher_id,u.full_name,c.room_code,
       (ARRAY['MON','TUE','WED','THU','FRI'])[(((n.slot_no+cl.offset_no)/5)::int%5)+1],((n.slot_no+cl.offset_no)%5)+1,
       CASE c.study_shift WHEN 'AFTERNOON' THEN (ARRAY['13:00','13:50','14:50','15:40','16:35'])[((n.slot_no+cl.offset_no)%5)+1] ELSE (ARRAY['07:00','07:50','08:50','09:40','10:35'])[((n.slot_no+cl.offset_no)%5)+1] END,
       CASE c.study_shift WHEN 'AFTERNOON' THEN (ARRAY['13:45','14:35','15:35','16:25','17:20'])[((n.slot_no+cl.offset_no)%5)+1] ELSE (ARRAY['07:45','08:35','09:35','10:25','11:20'])[((n.slot_no+cl.offset_no)%5)+1] END,false
FROM class_list cl CROSS JOIN numbered n JOIN users u ON u.id=n.teacher_id JOIN classes c ON c.id=cl.class_id;

-- Điểm thành phần và điểm học kỳ cho ba môn chính.
DELETE FROM grades;
WITH student_list AS (SELECT id,row_number() OVER(ORDER BY id) rn FROM users WHERE role='STUDENT' AND class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')),
assessments AS (SELECT * FROM (VALUES ('ORAL','Miệng',1),('15M','15 phút',1),('MID','Giữa kỳ',1),('FINAL','Cuối kỳ',1)) a(category,category_name,idx)),
subjects3 AS (SELECT * FROM (VALUES ('sj-math','Toán'),('sj-lit','Ngữ văn'),('sj-eng','Tiếng Anh')) s(subject_id,subject_name))
INSERT INTO grades (id,student_id,subject_id,subject_name,semester_id,category,category_name,assessment_index,score,note,recorded_at,created_at,created_by,updated_at,updated_by,version)
SELECT 'g26-'||st.rn||'-'||s.subject_id||'-'||a.category,st.id,s.subject_id,s.subject_name,'sm-2026-1',a.category,a.category_name,a.idx,
       round((6.5+((st.rn+length(a.category)+length(s.subject_id))%30)/10.0)::numeric,1),NULL,now()-interval '2 days',now(),'u-teacher-1',now(),'u-teacher-1',0
FROM student_list st CROSS JOIN assessments a CROSS JOIN subjects3 s;

-- Điểm danh có đủ trạng thái để kiểm tra thống kê.
DELETE FROM attendance_records;
WITH student_list AS (SELECT id,class_id,row_number() OVER(PARTITION BY class_id ORDER BY id) rn FROM users WHERE role='STUDENT' AND class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')),
first_slot AS (SELECT DISTINCT ON (class_id) id,class_id,subject_name,period_no FROM timetable_slots ORDER BY class_id,day_of_week,period_no)
INSERT INTO attendance_records (id,student_id,class_id,slot_id,date,status,note,subject_name,period_no,version,updated_at,updated_by)
SELECT 'att26-'||s.id,s.id,s.class_id,t.id,date '2026-08-24',CASE s.rn%5 WHEN 0 THEN 'ABSENT_EXCUSED' WHEN 1 THEN 'PRESENT' WHEN 2 THEN 'PRESENT' WHEN 3 THEN 'LATE' ELSE 'ABSENT_UNEXCUSED' END,
       CASE s.rn%5 WHEN 0 THEN 'Đã có đơn xin nghỉ' WHEN 3 THEN 'Đi học muộn 10 phút' WHEN 4 THEN 'Chưa xác nhận lý do' ELSE NULL END,t.subject_name,t.period_no,0,now(),'u-teacher-1'
FROM student_list s JOIN first_slot t ON t.class_id=s.class_id;

-- Bài tập, bài nộp và vòng đời.
DELETE FROM assignment_submissions; DELETE FROM assignments;
INSERT INTO assignments (id,title,description,class_id,subject_id,subject_name,teacher_id,teacher_name,deadline,allow_late,status,created_at,updated_at,version) VALUES
('asg-26-1','Ôn tập đại số','Hoàn thành bài 1 đến bài 10','c-10a1','sj-math','Toán','u-teacher-1','Nguyễn Đức Minh',now()+interval '5 days',true,'PUBLISHED',now(),now(),0),
('asg-26-2','Bài văn nghị luận','Viết bài nghị luận 600 chữ','c-10a1','sj-lit','Ngữ văn','u-teacher-3','Trần Thu Hà',now()+interval '7 days',false,'PUBLISHED',now(),now(),0),
('asg-26-3','English presentation','Chuẩn bị bài thuyết trình nhóm','c-10a2','sj-eng','Tiếng Anh','u-teacher-4','Lê Hoàng Anh',now()+interval '10 days',true,'PUBLISHED',now(),now(),0),
('asg-26-4','Thí nghiệm hóa học','Nộp báo cáo thí nghiệm','c-12a1','sj-chem','Hóa học','u-teacher-5','Nguyễn Ngọc Lan',now()-interval '2 days',false,'CLOSED',now()-interval '12 days',now(),0);
INSERT INTO assignment_submissions (id,assignment_id,student_id,student_name,content,status,submitted_at,score,feedback,graded_at,graded_by,resubmission_allowed,attempt_number,version)
SELECT 'sub-'||a.id||'-'||u.id,a.id,u.id,u.full_name,'Bài làm kiểm thử của '||u.full_name,
       CASE WHEN row_number() OVER(ORDER BY u.id)%3=0 THEN 'GRADED' ELSE 'SUBMITTED' END,now()-interval '1 day',
       CASE WHEN row_number() OVER(ORDER BY u.id)%3=0 THEN 8.5 ELSE NULL END,
       CASE WHEN row_number() OVER(ORDER BY u.id)%3=0 THEN 'Bài làm tốt, trình bày rõ ràng' ELSE NULL END,
       CASE WHEN row_number() OVER(ORDER BY u.id)%3=0 THEN now() ELSE NULL END,
       CASE WHEN row_number() OVER(ORDER BY u.id)%3=0 THEN a.teacher_id ELSE NULL END,false,1,0
FROM assignments a JOIN users u ON u.class_id=a.class_id AND u.role='STUDENT';

-- Đơn xin nghỉ với nhiều trạng thái.
DELETE FROM leave_requests;
INSERT INTO leave_requests (id,student_id,student_name,class_id,class_code,status,parent_id,parent_name,homeroom_teacher_id,homeroom_teacher_name,reason,decision_note,start_date,end_date,created_at,updated_at,parent_confirmed_at,decided_at) VALUES
('leave-26-1','u-student-1','Nguyễn Minh An','c-10a1','10A1','APPROVED','u-parent-1','Nguyễn Văn Hùng','u-teacher-1','Nguyễn Đức Minh','Khám bệnh theo lịch','Đồng ý nghỉ có phép',date '2026-08-25',date '2026-08-25',now()-interval '3 days',now(),now()-interval '2 days',now()-interval '1 day'),
('leave-26-2','u-student-101','Học sinh kiểm thử 01','c-10a1','10A1','PARENT_CONFIRMED','u-parent-101','Phụ huynh kiểm thử 01','u-teacher-1','Nguyễn Đức Minh','Việc gia đình',NULL,date '2026-08-28',date '2026-08-28',now()-interval '1 day',now(),now(),NULL),
('leave-26-3','u-student-105','Học sinh kiểm thử 05','c-10a2','10A2','REJECTED','u-parent-103','Phụ huynh kiểm thử 03','u-teacher-2','Lê Văn Minh','Đi du lịch','Không đủ căn cứ xác nhận',date '2026-09-01',date '2026-09-03',now()-interval '4 days',now(),now()-interval '3 days',now()-interval '2 days');

-- Thông báo và trao đổi.
INSERT INTO announcements (id,title,body,audience,category,priority,status,recipient_count,created_by,created_at,sent_at) VALUES
('an-26-start','Kế hoạch khai giảng năm học 2026-2027','Khai giảng lúc 07:00 ngày 05/09/2026 tại sân trường.','ALL','EVENT','IMPORTANT','SENT',73,'u-admin-1',now(),now()),
('an-26-meeting','Lịch họp phụ huynh đầu năm','Họp phụ huynh theo lớp vào sáng thứ Bảy tuần đầu tháng 9.','PARENT','PARENT_MEETING','IMPORTANT','SENT',16,'u-admin-1',now(),now());
INSERT INTO notifications (id,recipient_id,type,title,body,ref_type,ref_id,priority,read,created_at) VALUES
('noti-26-1','u-teacher-1','ATTENDANCE_REMINDER','Nhắc điểm danh','Tiết Toán lớp 10A1 sắp bắt đầu.','TIMETABLE_SLOT','tt26-10A1-1','HIGH',false,now()),
('noti-26-2','u-student-1','GRADE_PUBLISHED','Có điểm mới','Điểm giữa kỳ môn Toán đã được cập nhật.','GRADE','g26-1-sj-math-MID','NORMAL',false,now()),
('noti-26-3','u-parent-1','ATTENDANCE_ABSENT','Thông tin chuyên cần','Học sinh có cập nhật trạng thái chuyên cần mới.','ATTENDANCE','att26-u-student-1','HIGH',false,now());
INSERT INTO chat_messages (id,sender_id,sender_name,recipient_id,recipient_name,body,read_flag,read_at,created_at) VALUES
('chat-26-1','u-parent-1','Nguyễn Văn Hùng','u-teacher-1','Nguyễn Đức Minh','Thầy cho tôi hỏi tình hình học tập của cháu An.',true,now(),now()-interval '2 hours'),
('chat-26-2','u-teacher-1','Nguyễn Đức Minh','u-parent-1','Nguyễn Văn Hùng','Em An học tập ổn định, gia đình tiếp tục nhắc em hoàn thành bài tập.',false,NULL,now()-interval '1 hour'),
('chat-26-3','u-student-1','Nguyễn Minh An','u-student-101','Học sinh kiểm thử 01','Bạn gửi mình nội dung bài tập Toán nhé.',false,NULL,now()-interval '30 minutes');

-- Tài chính: hai đợt thu, hóa đơn đủ trạng thái và giao dịch mẫu.
TRUNCATE fee_period_items, invoice_items, payments, invoices, fee_periods CASCADE;
INSERT INTO fee_periods (id,code,name,academic_year_id,apply_to_grades,due_date,status,created_at) VALUES
('fee-26-1','HP-HK1-2026','Học phí học kỳ 1','ay-2026','K10,K11,K12',date '2026-09-15','OPEN',now()),
('fee-26-2','BHYT-2026','Bảo hiểm y tế năm học 2026-2027','ay-2026','K10,K11,K12',date '2026-09-30','ISSUED',now());
INSERT INTO fee_period_items (id,fee_period_id,name,grade_level,amount) VALUES
('fi-26-1','fee-26-1','Học phí học kỳ 1',NULL,3000000),('fi-26-2','fee-26-1','Phí cơ sở vật chất',NULL,500000),('fi-26-3','fee-26-2','Bảo hiểm y tế',NULL,680000);
WITH students AS (SELECT id,full_name,class_id,class_name,row_number() OVER(ORDER BY id) rn FROM users WHERE role='STUDENT' AND class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2'))
INSERT INTO invoices (id,code,fee_period_id,student_id,student_name,parent_id,total_amount,paid_amount,status,due_date,issued_at,version,class_id,class_code,grade_level)
SELECT 'inv-26-'||rn,'HD26-'||lpad(rn::text,4,'0'),'fee-26-1',s.id,s.full_name,(SELECT min(ps.parent_id) FROM parent_student ps WHERE ps.student_id=s.id),3500000,
       CASE rn%4 WHEN 0 THEN 3500000 WHEN 1 THEN 1500000 ELSE 0 END,
       CASE rn%4 WHEN 0 THEN 'PAID' WHEN 1 THEN 'PARTIAL' WHEN 2 THEN 'ISSUED' ELSE 'OVERDUE' END,date '2026-09-15',now(),0,s.class_id,s.class_name,left(s.class_name,2)
FROM students s;
INSERT INTO invoice_items (id,invoice_id,name,amount)
SELECT 'ii-'||id,id,'Học phí và cơ sở vật chất',total_amount FROM invoices;
INSERT INTO payments (id,invoice_id,amount,method,status,txn_ref,receipt_code,payer_name,note,recorded_by,created_at,paid_at)
SELECT 'pay-'||id,id,paid_amount,'VIETQR','SUCCESS','VQR-'||code,'PT-'||code,'Phụ huynh học sinh','Dữ liệu thanh toán kiểm thử','u-admin-1',now(),now()
FROM invoices WHERE paid_amount>0;

-- Khảo thí: ba môn chính trong hai ngày, phân phòng, SBD, chấm thi và phúc khảo.
DELETE FROM exam_review_requests; DELETE FROM exam_results; DELETE FROM exam_candidates; DELETE FROM exam_grading_assignments; DELETE FROM exam_rooms; DELETE FROM exam_schedule_classes; DELETE FROM exam_schedules; DELETE FROM exam_periods;
INSERT INTO exam_periods (id,code,name,academic_year_id,semester_id,grade_level,start_date,end_date,status,score_entry_locked,confirmed_at,confirmed_by,created_at,created_by,updated_at,schedule_published,schedule_revision,schedule_published_at,schedule_published_by,auto_generated) VALUES
('exam-mid-26','GK1-2026','Kiểm tra giữa học kỳ 1','ay-2026','sm-2026-1',NULL,date '2026-10-19',date '2026-10-20','CONFIRMED',false,now(),'u-admin-1',now(),'u-admin-1',now(),true,1,now(),'u-admin-1',true);
INSERT INTO exam_schedules (id,exam_period_id,subject_id,subject_name,exam_date,start_time,duration_minutes,notes) VALUES
('es-math-26','exam-mid-26','sj-math','Toán',date '2026-10-19','07:30',90,'Có mặt trước 15 phút'),
('es-lit-26','exam-mid-26','sj-lit','Ngữ văn',date '2026-10-19','13:30',90,'Có mặt trước 15 phút'),
('es-eng-26','exam-mid-26','sj-eng','Tiếng Anh',date '2026-10-20','07:30',60,'Có phần nghe');
INSERT INTO exam_schedule_classes (schedule_id,class_id)
SELECT e.id,c.id FROM exam_schedules e CROSS JOIN classes c WHERE c.id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2');
INSERT INTO exam_rooms (id,schedule_id,room_code,capacity,proctor_one_id,proctor_one_name,proctor_two_id,proctor_two_name)
SELECT 'er-'||e.id,e.id,'P301',40,'u-teacher-7','Vũ Thanh Tùng','u-teacher-8','Hoàng Hải Yến' FROM exam_schedules e;
WITH students AS (SELECT id,full_name,student_code,class_id,class_name,row_number() OVER(ORDER BY id) rn FROM users WHERE role='STUDENT' AND class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2'))
INSERT INTO exam_candidates (id,exam_period_id,schedule_id,exam_room_id,student_id,student_name,student_code,class_id,class_code,candidate_no,seat_no)
SELECT 'cand-'||e.id||'-'||s.rn,'exam-mid-26',e.id,'er-'||e.id,s.id,s.full_name,s.student_code,s.class_id,s.class_name,lpad((260000+s.rn)::text,6,'0'),s.rn
FROM exam_schedules e CROSS JOIN students s;
INSERT INTO exam_grading_assignments (id,exam_period_id,schedule_id,class_id,class_code,subject_id,subject_name,teacher_id,teacher_name,assigned_at,assigned_by)
SELECT 'ega-'||e.id||'-'||c.id,'exam-mid-26',e.id,c.id,c.code,e.subject_id,e.subject_name,
       CASE e.subject_id WHEN 'sj-math' THEN 'u-teacher-1' WHEN 'sj-lit' THEN 'u-teacher-3' ELSE 'u-teacher-4' END,
       CASE e.subject_id WHEN 'sj-math' THEN 'Nguyễn Đức Minh' WHEN 'sj-lit' THEN 'Trần Thu Hà' ELSE 'Lê Hoàng Anh' END,now(),'u-admin-1'
FROM exam_schedules e CROSS JOIN classes c WHERE c.id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2');
INSERT INTO exam_results (id,exam_period_id,schedule_id,student_id,subject_id,score,status,note,recorded_at,recorded_by,updated_at,updated_by,version)
SELECT 'result-'||c.id,'exam-mid-26',c.schedule_id,c.student_id,e.subject_id,round((6.0+(c.seat_no%35)/10.0)::numeric,1),'PUBLISHED','Dữ liệu kiểm thử',now(),
       CASE e.subject_id WHEN 'sj-math' THEN 'u-teacher-1' WHEN 'sj-lit' THEN 'u-teacher-3' ELSE 'u-teacher-4' END,now(),
       CASE e.subject_id WHEN 'sj-math' THEN 'u-teacher-1' WHEN 'sj-lit' THEN 'u-teacher-3' ELSE 'u-teacher-4' END,0
FROM exam_candidates c JOIN exam_schedules e ON e.id=c.schedule_id;
INSERT INTO exam_review_requests (id,exam_period_id,result_id,student_id,student_name,subject_id,subject_name,original_score,reason,status,requested_at,requested_by)
SELECT 'review-26-1','exam-mid-26',r.id,r.student_id,u.full_name,r.subject_id,s.name,r.score,'Đề nghị kiểm tra lại câu tự luận','PENDING',now(),r.student_id
FROM exam_results r JOIN users u ON u.id=r.student_id JOIN subjects s ON s.id=r.subject_id LIMIT 1;

-- Tổng kết năm ở trạng thái dự kiến, đủ hai học kỳ để thử xét duyệt.
INSERT INTO student_yearly_summaries (id,academic_year_id,student_id,student_name,class_id,semester_one_average,semester_two_average,average_score,conduct_grade,conduct_note,promotion_status,missing_requirements,updated_at,conduct_updated_by,version)
SELECT 'sum26-'||u.id,'ay-2026',u.id,u.full_name,u.class_id,round((7.0+(row_number() OVER(ORDER BY u.id)%20)/10.0)::numeric,1),
       round((7.2+(row_number() OVER(ORDER BY u.id)%18)/10.0)::numeric,1),round((7.1+(row_number() OVER(ORDER BY u.id)%19)/10.0)::numeric,1),
       CASE WHEN row_number() OVER(ORDER BY u.id)%5=0 THEN 'GOOD' ELSE 'EXCELLENT' END,'Đánh giá kiểm thử','ELIGIBLE',NULL,now(),'u-teacher-1',0
FROM users u WHERE u.role='STUDENT' AND u.class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')
ON CONFLICT DO NOTHING;

INSERT INTO school_holidays (id,date,name,description) VALUES
('holiday-2026-09-02',date '2026-09-02','Quốc khánh','Toàn trường nghỉ học'),
('holiday-2027-01-01',date '2027-01-01','Tết Dương lịch','Toàn trường nghỉ học')
ON CONFLICT DO NOTHING;

-- Loại bỏ các bản ghi tối thiểu do seed nền tạo ra; chỉ giữ bộ dữ liệu 2026-2027 ở trên.
DELETE FROM parent_student WHERE student_id='u-student-2';
DELETE FROM class_enrollments WHERE student_id='u-student-2';
DELETE FROM timetable_slots WHERE class_id='c-8a1';
DELETE FROM users WHERE id='u-student-2';
DELETE FROM classes WHERE id='c-8a1';
DELETE FROM announcements WHERE id IN ('an-1','an-2');
DELETE FROM chat_messages WHERE id IN ('msg-1','msg-2','msg-3');
DELETE FROM audit_logs;
INSERT INTO audit_logs (id,actor_id,actor_name,role,action,module,entity_type,entity_id,detail,created_at) VALUES
('audit-26-1','u-admin-1','Nguyễn Văn Quản','ADMIN','CREATE','academic','academic_year','ay-2026','Khởi tạo niên khóa 2026-2027',now()-interval '3 days'),
('audit-26-2','u-admin-1','Nguyễn Văn Quản','ADMIN','AUTO_SCHEDULE','timetable','semester','sm-2026-1','Tạo tự động 180 tiết thời khóa biểu',now()-interval '2 days'),
('audit-26-3','u-teacher-1','Nguyễn Đức Minh','TEACHER','UPDATE','academic','grade','g26-1-sj-math-MID','Cập nhật điểm giữa kỳ môn Toán',now()-interval '1 day'),
('audit-26-4','u-admin-1','Nguyễn Văn Quản','ADMIN','ISSUE','finance','fee_period','fee-26-1','Phát hành đợt thu học kỳ 1',now());

COMMIT;
