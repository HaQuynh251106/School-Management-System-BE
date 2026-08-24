BEGIN;
SELECT pg_advisory_xact_lock(hashtext('sse-full-demo-mobile-seed-2027-2028'));

-- This is an additive extension of full-demo.sql. It is safe to execute on an
-- already running demo database and intentionally never deletes business data.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM academic_years WHERE id = 'fd-ay-2027')
       OR NOT EXISTS (SELECT 1 FROM users WHERE id = 'fd-student-041')
       OR NOT EXISTS (SELECT 1 FROM academic_training_plans WHERE id = 'fd-plan-k12-v2') THEN
        RAISE EXCEPTION 'Full Demo base data is missing; run dataset full-demo before full-demo-mobile';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Assignments and submissions for the representative Mobile accounts.
-- Teacher 002/003 receive their own work queues; K11/K12 students and parents
-- receive class-scoped assignments from teachers who are actually assigned.
-- ---------------------------------------------------------------------------
INSERT INTO assignments (
    id,allow_late,class_id,created_at,deadline,description,status,
    subject_id,subject_name,teacher_id,teacher_name,title,reminder_count,updated_at
)
SELECT seed.id, seed.allow_late, seed.class_id, now() - seed.created_before,
       now() + seed.deadline_offset, seed.description, seed.status,
       assignment.subject_id, assignment.subject_name,
       assignment.teacher_id, assignment.teacher_name,
       seed.title, seed.reminder_count, now()
FROM (VALUES
    ('fd-mobile-assignment-lit-draft', false, 'fd-class-10a2', 'LIT',
     interval '1 day', interval '14 days',
     'Bản nháp để kiểm tra thao tác tiếp tục biên soạn trên Mobile.',
     'DRAFT', 'Bài tập nháp · Đọc hiểu văn bản', 0),
    ('fd-mobile-assignment-lit-10a2', true, 'fd-class-10a2', 'LIT',
     interval '5 days', interval '10 days',
     'Đọc văn bản, trả lời câu hỏi và nộp bài trước hạn.',
     'PUBLISHED', 'Ngữ văn · Đọc hiểu đầu năm', 1),
    ('fd-mobile-assignment-eng-10a1', true, 'fd-class-10a1', 'ENG',
     interval '4 days', interval '9 days',
     'Hoàn thành bài luyện từ vựng và phần viết ngắn.',
     'PUBLISHED', 'English · School life', 1),
    ('fd-mobile-assignment-eng-11a1', true, 'fd-class-11a1', 'ENG',
     interval '4 days', interval '8 days',
     'Hoàn thành bài đọc hiểu và viết đoạn văn 120 từ.',
     'PUBLISHED', 'English 11 · Reading and writing', 1),
    ('fd-mobile-assignment-eng-12a1', true, 'fd-class-12a1', 'ENG',
     interval '8 days', interval '-1 day',
     'Bài luyện tổng hợp cho kỳ kiểm tra; vẫn cho phép nộp muộn.',
     'PUBLISHED', 'English 12 · Exam practice', 2)
) seed(id,allow_late,class_id,subject_code,created_before,deadline_offset,
       description,status,title,reminder_count)
JOIN subjects subject ON subject.code = seed.subject_code
JOIN teacher_class_subjects assignment
  ON assignment.class_id = seed.class_id
 AND assignment.subject_id = subject.id
 AND assignment.semester_id = 'fd-sem-2027-1'
 AND assignment.status = 'ACTIVE'
ON CONFLICT (id) DO UPDATE SET
    allow_late = excluded.allow_late,
    class_id = excluded.class_id,
    deadline = excluded.deadline,
    description = excluded.description,
    status = excluded.status,
    subject_id = excluded.subject_id,
    subject_name = excluded.subject_name,
    teacher_id = excluded.teacher_id,
    teacher_name = excluded.teacher_name,
    title = excluded.title,
    reminder_count = excluded.reminder_count,
    updated_at = now();

INSERT INTO assignment_submissions (
    id,assignment_id,content,feedback,graded_at,graded_by,score,status,
    student_id,student_name,submitted_at,current_version
)
VALUES
 ('fd-mobile-submission-011','fd-mobile-assignment-lit-10a2',
  'Bài đọc hiểu đã hoàn thành và nộp đúng hạn.',
  'Lập luận rõ; cần trích dẫn chính xác hơn.',now()-interval '1 day',
  'fd-teacher-002',8.4,'GRADED','fd-student-011','Học sinh Demo 011',
  now()-interval '2 days',1),
 ('fd-mobile-submission-012','fd-mobile-assignment-lit-10a2',
  'Bài làm đang chờ giáo viên chấm.',null,null,null,null,'SUBMITTED',
  'fd-student-012','Học sinh Demo 012',now()-interval '1 day',1),
 ('fd-mobile-submission-001-eng','fd-mobile-assignment-eng-10a1',
  'Vocabulary and short paragraph completed.',
  'Good vocabulary; check the final sentence.',now()-interval '1 day',
  'fd-teacher-003',8.8,'GRADED','fd-student-001','Học sinh Demo 001',
  now()-interval '2 days',1),
 ('fd-mobile-submission-021','fd-mobile-assignment-eng-11a1',
  'Reading answers and a 120-word paragraph.',
  'Ý đầy đủ, dùng từ tốt.',now()-interval '12 hours',
  'fd-teacher-015',9.2,'GRADED','fd-student-021','Học sinh Demo 021',
  now()-interval '1 day',1),
 ('fd-mobile-submission-025','fd-mobile-assignment-eng-11a1',
  'Bài làm đã gửi, đang chờ nhận xét.',null,null,null,null,'SUBMITTED',
  'fd-student-025','Học sinh Demo 025',now()-interval '10 hours',1),
 ('fd-mobile-submission-041','fd-mobile-assignment-eng-12a1',
  'Bài luyện thi được nộp sau thời hạn.',null,null,null,null,'LATE',
  'fd-student-041','Học sinh Demo 041',now()-interval '6 hours',1),
 ('fd-mobile-submission-045','fd-mobile-assignment-eng-12a1',
  'Bài luyện thi hoàn chỉnh.',
  'Nắm chắc cấu trúc; chú ý phần đọc hiểu câu 5.',now()-interval '3 hours',
  'fd-teacher-027',8.4,'GRADED','fd-student-045','Học sinh Demo 045',
  now()-interval '1 day 2 hours',1)
ON CONFLICT (id) DO UPDATE SET
    assignment_id=excluded.assignment_id,content=excluded.content,
    feedback=excluded.feedback,graded_at=excluded.graded_at,
    graded_by=excluded.graded_by,score=excluded.score,status=excluded.status,
    student_id=excluded.student_id,student_name=excluded.student_name,
    submitted_at=excluded.submitted_at,current_version=1;

INSERT INTO assignment_submission_versions (
    id,content,submission_id,submitted_at,submitted_by,version_no
)
SELECT s.id||'-v1',s.content,s.id,s.submitted_at,s.student_id,1
FROM assignment_submissions s
WHERE s.id LIKE 'fd-mobile-submission-%'
ON CONFLICT (submission_id,version_no) DO UPDATE SET
    content=excluded.content,submitted_at=excluded.submitted_at,
    submitted_by=excluded.submitted_by;

-- ---------------------------------------------------------------------------
-- Attendance and excuse requests for each representative teacher/student/
-- parent scope. Every record references a real published timetable slot.
-- ---------------------------------------------------------------------------
WITH mobile_attendance(id,student_id,slot_id,days_before,status,note,late_minutes) AS (
    VALUES
      ('fd-mobile-attendance-001','fd-student-001','fd-slot-10a1-05',6,
       'ABSENT_EXCUSED','Nghỉ ốm, phụ huynh đã gửi xác nhận',0),
      ('fd-mobile-attendance-011','fd-student-011','fd-slot-10a2-03',5,
       'ABSENT_UNEXCUSED','Vắng học chưa có đơn xác nhận',0),
      ('fd-mobile-attendance-021','fd-student-021','fd-slot-11a1-05',4,
       'ABSENT_EXCUSED','Nghỉ khám bệnh, đã bổ sung xác nhận',0),
      ('fd-mobile-attendance-025','fd-student-025','fd-slot-11a1-22',3,
       'LATE','Đến muộn do xe gia đình gặp sự cố',15),
      ('fd-mobile-attendance-041','fd-student-041','fd-slot-12a1-05',2,
       'LATE','Đến muộn do tắc đường',10),
      ('fd-mobile-attendance-045','fd-student-045','fd-slot-12a1-01',1,
       'ABSENT_UNEXCUSED','Gia đình chưa gửi lý do nghỉ',0)
)
INSERT INTO attendance_records (
    id,class_id,date,note,period_no,slot_id,status,student_id,subject_name,late_minutes
)
SELECT seed.id, slot.class_id, current_date-seed.days_before, seed.note,
       slot.period_no, slot.id, seed.status, seed.student_id,
       slot.subject_name, seed.late_minutes
FROM mobile_attendance seed
JOIN timetable_slots slot ON slot.id=seed.slot_id
ON CONFLICT (id) DO UPDATE SET
    class_id=excluded.class_id,date=excluded.date,note=excluded.note,
    period_no=excluded.period_no,slot_id=excluded.slot_id,status=excluded.status,
    student_id=excluded.student_id,subject_name=excluded.subject_name,
    late_minutes=excluded.late_minutes;

INSERT INTO attendance_excuse_requests (
    id,attendance_record_id,reason,requested_at,requested_by,requester_role,
    review_note,reviewed_at,reviewed_by,status,student_id
)
VALUES
 ('fd-mobile-excuse-001','fd-mobile-attendance-001',
  'Gia đình xin phép cho học sinh nghỉ do bị sốt.',now()-interval '5 days',
  'fd-parent-001','PARENT','Đã xác nhận với phụ huynh.',
  now()-interval '4 days','fd-teacher-001','APPROVED','fd-student-001'),
 ('fd-mobile-excuse-011','fd-mobile-attendance-011',
  'Gia đình xin xác nhận học sinh nghỉ do sốt.',now()-interval '4 days',
  'fd-parent-006','PARENT',null,null,null,'PENDING','fd-student-011'),
 ('fd-mobile-excuse-021','fd-mobile-attendance-021',
  'Em nghỉ khám bệnh theo lịch hẹn của bệnh viện.',now()-interval '3 days',
  'fd-student-021','STUDENT','Đã kiểm tra thông tin và chấp nhận.',
  now()-interval '2 days','fd-teacher-015','APPROVED','fd-student-021'),
 ('fd-mobile-excuse-025','fd-mobile-attendance-025',
  'Xe gia đình gặp sự cố trên đường đến trường.',now()-interval '2 days',
  'fd-parent-013','PARENT',null,null,null,'PENDING','fd-student-025'),
 ('fd-mobile-excuse-041','fd-mobile-attendance-041',
  'Em xin xác nhận đi muộn vì tuyến xe buýt thay đổi.',now()-interval '1 day',
  'fd-student-041','STUDENT',null,null,null,'PENDING','fd-student-041'),
 ('fd-mobile-excuse-045','fd-mobile-attendance-045',
  'Học sinh nghỉ khám bệnh và đã có xác nhận gia đình.',now()-interval '12 hours',
  'fd-parent-033','PARENT','Đã trao đổi với phụ huynh.',
  now()-interval '6 hours','fd-teacher-005','APPROVED','fd-student-045')
ON CONFLICT (id) DO UPDATE SET
    attendance_record_id=excluded.attendance_record_id,reason=excluded.reason,
    requested_at=excluded.requested_at,requested_by=excluded.requested_by,
    requester_role=excluded.requester_role,review_note=excluded.review_note,
    reviewed_at=excluded.reviewed_at,reviewed_by=excluded.reviewed_by,
    status=excluded.status,student_id=excluded.student_id;

-- Grade history is tied to an existing grade and its correctly assigned teacher.
UPDATE grades SET score=8.4,note='Đã sửa sau khi đối chiếu bài kiểm tra',recorded_at=now()-interval '2 days'
WHERE id='fd-grade-011-lit-final';
UPDATE grades SET score=8.8,note='Đã sửa theo biên bản chấm lại',recorded_at=now()-interval '2 days'
WHERE id='fd-grade-001-eng-mid';
UPDATE grades SET score=9.2,note='Đã sửa sau phúc tra',recorded_at=now()-interval '2 days'
WHERE id='fd-grade-021-eng-mid';
UPDATE grades SET score=8.4,note='Đã sửa sau khi nhập nhầm',recorded_at=now()-interval '2 days'
WHERE id='fd-grade-041-eng-final';

INSERT INTO grade_change_logs (
    id,changed_at,changed_by,grade_id,new_note,new_score,old_note,old_score,reason
)
VALUES
 ('fd-mobile-grade-log-011',now()-interval '2 days','fd-teacher-002',
  'fd-grade-011-lit-final','Đã sửa sau khi đối chiếu bài kiểm tra',8.4,
  'Điểm nhập lần đầu',7.4,'Đối chiếu lại phiếu chấm và bài làm gốc'),
 ('fd-mobile-grade-log-001-eng',now()-interval '2 days','fd-teacher-003',
  'fd-grade-001-eng-mid','Đã sửa theo biên bản chấm lại',8.8,
  'Điểm nhập lần đầu',8.4,'Giáo viên rà soát lại đáp án phần viết'),
 ('fd-mobile-grade-log-021',now()-interval '2 days','fd-teacher-015',
  'fd-grade-021-eng-mid','Đã sửa sau phúc tra',9.2,
  'Điểm nhập lần đầu',8.4,'Cộng lại điểm phần đọc hiểu bị bỏ sót'),
 ('fd-mobile-grade-log-041',now()-interval '2 days','fd-teacher-027',
  'fd-grade-041-eng-final','Đã sửa sau khi nhập nhầm',8.4,
  'Điểm nhập lần đầu',6.4,'Sửa lỗi nhập nhầm điểm từ phiếu chấm')
ON CONFLICT (id) DO UPDATE SET
    changed_at=excluded.changed_at,changed_by=excluded.changed_by,
    grade_id=excluded.grade_id,new_note=excluded.new_note,
    new_score=excluded.new_score,old_note=excluded.old_note,
    old_score=excluded.old_score,reason=excluded.reason;

INSERT INTO homeroom_remarks (
    id,student_id,class_id,academic_year_id,semester_id,teacher_id,body,status,
    published_at,created_at,updated_at
)
VALUES
 ('fd-mobile-remark-021','fd-student-021','fd-class-11a1','fd-ay-2027','fd-sem-2027-1',
  'fd-teacher-003','Em học tập nghiêm túc, cần duy trì việc nộp bài đúng hạn.',
  'PUBLISHED',now()-interval '1 day',now()-interval '2 days',now()-interval '1 day'),
 ('fd-mobile-remark-025','fd-student-025','fd-class-11a1','fd-ay-2027','fd-sem-2027-1',
  'fd-teacher-003','Em có tiến bộ, cần chủ động trao đổi khi chưa hiểu bài.',
  'PUBLISHED',now()-interval '1 day',now()-interval '2 days',now()-interval '1 day'),
 ('fd-mobile-remark-041','fd-student-041','fd-class-12a1','fd-ay-2027','fd-sem-2027-1',
  'fd-teacher-005','Em có ý thức ôn tập tốt, cần chú ý giờ vào lớp.',
  'PUBLISHED',now()-interval '1 day',now()-interval '2 days',now()-interval '1 day'),
 ('fd-mobile-remark-045','fd-student-045','fd-class-12a1','fd-ay-2027','fd-sem-2027-1',
  'fd-teacher-005','Em hoàn thành nhiệm vụ học tập, cần bổ sung đơn xin phép đúng hạn.',
  'PUBLISHED',now()-interval '1 day',now()-interval '2 days',now()-interval '1 day')
ON CONFLICT (student_id,semester_id) DO UPDATE SET
    class_id=excluded.class_id,teacher_id=excluded.teacher_id,body=excluded.body,
    status='PUBLISHED',published_at=excluded.published_at,updated_at=excluded.updated_at;

-- ---------------------------------------------------------------------------
-- Published K11 and K12 exam schedules. Dates are separated from K10 and from
-- each other, so room/proctor conflicts are impossible. Source assessment-plan
-- links remain canonical and make the schedules auditable.
-- ---------------------------------------------------------------------------
INSERT INTO exam_periods (
    id,code,name,academic_year_id,semester_id,exam_type,status,scope_grades,
    allow_subject_teacher_proctor,start_date,end_date,published_version_id,
    created_by,created_at,updated_at
)
VALUES
 ('fd-mobile-exam-period-k11','DEMO-MOBILE-HK1-K11','Kiểm tra giữa HK1 khối 11',
  'fd-ay-2027','fd-sem-2027-1','MIDTERM','PUBLISHED','K11',false,
  DATE '2027-11-15',DATE '2027-11-19',null,'fd-admin-001',now()-interval '18 days',now()),
 ('fd-mobile-exam-period-k12','DEMO-MOBILE-HK1-K12','Kiểm tra giữa HK1 khối 12',
  'fd-ay-2027','fd-sem-2027-1','MIDTERM','PUBLISHED','K12',false,
  DATE '2027-11-22',DATE '2027-11-26',null,'fd-admin-001',now()-interval '18 days',now())
ON CONFLICT (id) DO UPDATE SET
    name=excluded.name,status='PUBLISHED',scope_grades=excluded.scope_grades,
    start_date=excluded.start_date,end_date=excluded.end_date,
    published_version_id=null,updated_at=now();

INSERT INTO exam_schedule_versions (
    id,exam_period_id,version_no,status,based_on_version_id,change_reason,
    created_by,created_at,published_by,published_at,content_updated_at,
    last_validated_at,last_validation_error_count,last_validation_warning_count
)
VALUES
 ('fd-mobile-exam-k11-v1','fd-mobile-exam-period-k11',1,'ARCHIVED',null,
  'Bản đầu trước khi rà soát giám thị.','fd-admin-001',now()-interval '17 days',
  null,null,now()-interval '16 days',now()-interval '16 days',0,1),
 ('fd-mobile-exam-k11-v2','fd-mobile-exam-period-k11',2,'PUBLISHED','fd-mobile-exam-k11-v1',
  'Đã kiểm tra toàn bộ xung đột.','fd-admin-001',now()-interval '15 days',
  'fd-admin-001',now()-interval '14 days',now()-interval '14 days',
  now()-interval '14 days',0,0),
 ('fd-mobile-exam-k12-v1','fd-mobile-exam-period-k12',1,'ARCHIVED',null,
  'Bản đầu trước khi rà soát giám thị.','fd-admin-001',now()-interval '17 days',
  null,null,now()-interval '16 days',now()-interval '16 days',0,1),
 ('fd-mobile-exam-k12-v2','fd-mobile-exam-period-k12',2,'PUBLISHED','fd-mobile-exam-k12-v1',
  'Đã kiểm tra toàn bộ xung đột.','fd-admin-001',now()-interval '15 days',
  'fd-admin-001',now()-interval '14 days',now()-interval '14 days',
  now()-interval '14 days',0,0)
ON CONFLICT (id) DO UPDATE SET
    status=excluded.status,based_on_version_id=excluded.based_on_version_id,
    change_reason=excluded.change_reason,published_by=excluded.published_by,
    published_at=excluded.published_at,content_updated_at=excluded.content_updated_at,
    last_validated_at=excluded.last_validated_at,
    last_validation_error_count=excluded.last_validation_error_count,
    last_validation_warning_count=excluded.last_validation_warning_count;

UPDATE exam_periods SET published_version_id='fd-mobile-exam-k11-v2',updated_at=now()
WHERE id='fd-mobile-exam-period-k11';
UPDATE exam_periods SET published_version_id='fd-mobile-exam-k12-v2',updated_at=now()
WHERE id='fd-mobile-exam-period-k12';

WITH grade_seed(grade_level,grade_key,version_id,plan_id,plan_version,plan_name,start_date) AS (
    VALUES
      ('K11','k11','fd-mobile-exam-k11-v2','fd-plan-k11-v2',2,
       'Kế hoạch giáo dục Khối 11 · phiên bản 2',DATE '2027-11-15'),
      ('K12','k12','fd-mobile-exam-k12-v2','fd-plan-k12-v2',2,
       'Kế hoạch giáo dục Khối 12 · phiên bản 2',DATE '2027-11-22')
), exam_subject(code,day_offset,duration) AS (
    VALUES ('MATH',0,90),('LIT',1,90),('ENG',2,60),('PHYS',3,60),('CHEM',4,60)
)
INSERT INTO exam_sessions (
    id,version_id,subject_id,grade_level,exam_date,start_time,duration_minutes,
    notes,created_at,updated_at,source_assessment_plan_id,source_training_plan_id,
    source_plan_version,source_plan_name,source_plan_status,source_assessment_name,
    source_assessment_type,source_assessment_form,source_assessment_week,
    source_planned_start_date,source_planned_end_date,source_synced_at,source_updated_at
)
SELECT 'fd-mobile-exam-session-'||g.grade_key||'-'||lower(e.code),
       g.version_id,s.id,g.grade_level,g.start_date+e.day_offset,TIME '07:30',e.duration,
       'Ca thi Mobile Demo không xung đột.',now(),now(),a.id,g.plan_id,g.plan_version,
       g.plan_name,'PUBLISHED',a.name,a.assessment_type,a.assessment_form,a.week_number,
       ps.start_date,ps.end_date,now(),now()
FROM grade_seed g CROSS JOIN exam_subject e
JOIN subjects s ON s.code=e.code
JOIN academic_training_plan_subjects ps ON ps.plan_id=g.plan_id
 AND ps.semester_id='fd-sem-2027-1' AND ps.subject_id=s.id
JOIN academic_assessment_plans a ON a.plan_id=g.plan_id
 AND a.semester_id='fd-sem-2027-1' AND a.subject_id=s.id
 AND a.assessment_type='MIDTERM'
ON CONFLICT (version_id,subject_id,grade_level) DO UPDATE SET
    exam_date=excluded.exam_date,start_time=excluded.start_time,
    duration_minutes=excluded.duration_minutes,notes=excluded.notes,
    source_assessment_plan_id=excluded.source_assessment_plan_id,
    source_training_plan_id=excluded.source_training_plan_id,
    source_plan_version=excluded.source_plan_version,
    source_plan_name=excluded.source_plan_name,source_plan_status=excluded.source_plan_status,
    source_assessment_name=excluded.source_assessment_name,
    source_assessment_type=excluded.source_assessment_type,
    source_assessment_form=excluded.source_assessment_form,
    source_assessment_week=excluded.source_assessment_week,
    source_planned_start_date=excluded.source_planned_start_date,
    source_planned_end_date=excluded.source_planned_end_date,
    source_synced_at=excluded.source_synced_at,source_updated_at=excluded.source_updated_at,
    updated_at=now();

WITH proctor_seed(grade_key,subject_code,primary_id,backup_id) AS (
    VALUES
      ('k11','MATH','fd-teacher-002','fd-teacher-006'),
      ('k11','LIT','fd-teacher-003','fd-teacher-007'),
      ('k11','ENG','fd-teacher-001','fd-teacher-008'),
      ('k11','PHYS','fd-teacher-004','fd-teacher-009'),
      ('k11','CHEM','fd-teacher-005','fd-teacher-010'),
      ('k12','MATH','fd-teacher-001','fd-teacher-013'),
      ('k12','LIT','fd-teacher-002','fd-teacher-014'),
      ('k12','ENG','fd-teacher-003','fd-teacher-015'),
      ('k12','PHYS','fd-teacher-004','fd-teacher-016'),
      ('k12','CHEM','fd-teacher-005','fd-teacher-017')
)
INSERT INTO exam_room_assignments (
    id,session_id,room_id,capacity_snapshot,primary_proctor_id,backup_proctor_id,
    created_at,updated_at
)
SELECT 'fd-mobile-exam-room-'||p.grade_key||'-'||lower(p.subject_code),
       session.id,'fd-room-exam-01',40,p.primary_id,p.backup_id,now(),now()
FROM proctor_seed p
JOIN subjects subject ON subject.code=p.subject_code
JOIN exam_sessions session
  ON session.id='fd-mobile-exam-session-'||p.grade_key||'-'||lower(p.subject_code)
 AND session.subject_id=subject.id
ON CONFLICT (session_id,room_id) DO UPDATE SET
    primary_proctor_id=excluded.primary_proctor_id,
    backup_proctor_id=excluded.backup_proctor_id,updated_at=now();

WITH grade_students(grade_key,first_no,last_no) AS (
    VALUES ('k11',21,40),('k12',41,59)
)
INSERT INTO exam_room_students (
    id,session_id,room_assignment_id,student_id,student_code,student_name,
    class_id,class_code,seat_no
)
SELECT 'fd-mobile-exam-seat-'||g.grade_key||'-'||lower(subject.code)||'-'||
       lpad(student_no::text,3,'0'),session.id,room_assignment.id,
       student.id,student.student_code,student.full_name,
       student.class_id,student.class_name,
       row_number() OVER (PARTITION BY session.id ORDER BY student_no)::integer
FROM grade_students g
CROSS JOIN LATERAL generate_series(g.first_no,g.last_no) student_no
JOIN users student ON student.id='fd-student-'||lpad(student_no::text,3,'0')
JOIN exam_sessions session ON session.id LIKE 'fd-mobile-exam-session-'||g.grade_key||'-%'
JOIN subjects subject ON subject.id=session.subject_id
JOIN exam_room_assignments room_assignment ON room_assignment.session_id=session.id
ON CONFLICT (session_id,student_id) DO UPDATE SET
    room_assignment_id=excluded.room_assignment_id,student_code=excluded.student_code,
    student_name=excluded.student_name,class_id=excluded.class_id,
    class_code=excluded.class_code,seat_no=excluded.seat_no;

-- ---------------------------------------------------------------------------
-- Finance data for the representative K11/K12 parents. Parent 013 has a
-- payable invoice and a failed attempt; Parent 033 has a partial cash payment.
-- ---------------------------------------------------------------------------
INSERT INTO fee_periods (
    id,academic_year_id,apply_to_grades,code,created_at,due_date,name,status,
    target_type,published_at,fee_type,semester_id
)
VALUES
 ('fd-mobile-fee-hs025','fd-ay-2027','K11','DEMO-MOBILE-HS025',now()-interval '10 days',
  current_date+14,'Hoạt động trải nghiệm khối 11','PUBLISHED','STUDENT',
  now()-interval '9 days','ACTIVITY','fd-sem-2027-1'),
 ('fd-mobile-fee-hs045','fd-ay-2027','K12','DEMO-MOBILE-HS045',now()-interval '12 days',
  current_date+10,'Ôn tập và học liệu khối 12','PUBLISHED','STUDENT',
  now()-interval '11 days','TUITION','fd-sem-2027-1')
ON CONFLICT (id) DO UPDATE SET
    apply_to_grades=excluded.apply_to_grades,due_date=excluded.due_date,
    name=excluded.name,status='PUBLISHED',target_type='STUDENT',
    published_at=excluded.published_at,fee_type=excluded.fee_type,
    semester_id=excluded.semester_id;

INSERT INTO fee_period_targets (id,fee_period_id,target_id,target_type)
VALUES
 ('fd-mobile-fee-target-025','fd-mobile-fee-hs025','fd-student-025','STUDENT'),
 ('fd-mobile-fee-target-045','fd-mobile-fee-hs045','fd-student-045','STUDENT')
ON CONFLICT (fee_period_id,target_type,target_id) DO NOTHING;

INSERT INTO fee_period_items (id,amount,fee_period_id,grade_level,name,target_type)
VALUES
 ('fd-mobile-fee-item-025-activity',480000,'fd-mobile-fee-hs025','K11',
  'Hoạt động trải nghiệm', 'STUDENT'),
 ('fd-mobile-fee-item-045-review',650000,'fd-mobile-fee-hs045','K12',
  'Học liệu ôn tập cuối cấp', 'STUDENT')
ON CONFLICT (id) DO UPDATE SET
    amount=excluded.amount,fee_period_id=excluded.fee_period_id,
    grade_level=excluded.grade_level,name=excluded.name,target_type='STUDENT';

INSERT INTO fee_period_item_targets (id,fee_period_item_id,target_id,target_type)
VALUES
 ('fd-mobile-fit-025','fd-mobile-fee-item-025-activity','fd-student-025','STUDENT'),
 ('fd-mobile-fit-045','fd-mobile-fee-item-045-review','fd-student-045','STUDENT')
ON CONFLICT (fee_period_item_id,target_type,target_id) DO NOTHING;

INSERT INTO invoices (
    id,code,due_date,fee_period_id,issued_at,paid_amount,parent_id,status,
    student_id,student_name,total_amount,reminder_count
)
VALUES
 ('fd-mobile-invoice-025','HD-MOBILE-025',current_date+14,'fd-mobile-fee-hs025',
  now()-interval '8 days',0,'fd-parent-013','PENDING','fd-student-025',
  'Học sinh Demo 025',480000,1),
 ('fd-mobile-invoice-045','HD-MOBILE-045',current_date+10,'fd-mobile-fee-hs045',
  now()-interval '10 days',300000,'fd-parent-033','PARTIAL','fd-student-045',
  'Học sinh Demo 045',650000,0)
ON CONFLICT (id) DO UPDATE SET
    due_date=excluded.due_date,fee_period_id=excluded.fee_period_id,
    paid_amount=excluded.paid_amount,parent_id=excluded.parent_id,
    status=excluded.status,student_id=excluded.student_id,
    student_name=excluded.student_name,total_amount=excluded.total_amount,
    reminder_count=excluded.reminder_count;

INSERT INTO invoice_items (id,amount,invoice_id,name,fee_period_item_id,source_target_type)
VALUES
 ('fd-mobile-invoice-item-025',480000,'fd-mobile-invoice-025',
  'Hoạt động trải nghiệm','fd-mobile-fee-item-025-activity','STUDENT'),
 ('fd-mobile-invoice-item-045',650000,'fd-mobile-invoice-045',
  'Học liệu ôn tập cuối cấp','fd-mobile-fee-item-045-review','STUDENT')
ON CONFLICT (id) DO UPDATE SET
    amount=excluded.amount,invoice_id=excluded.invoice_id,name=excluded.name,
    fee_period_item_id=excluded.fee_period_item_id,
    source_target_type=excluded.source_target_type;

INSERT INTO payments (
    id,invoice_id,amount,method,status,txn_ref,note,created_at,updated_at,paid_at,
    bank_qr_url,bank_transfer_content,auto_provisioned
)
VALUES
 ('fd-mobile-payment-failed-025','fd-mobile-invoice-025',480000,
  'MB_BANK_TRANSFER','FAILED','DEMO-MOBILE-FAILED-025',
  'Giao dịch thử thất bại; hóa đơn vẫn có thể tạo yêu cầu thanh toán lại.',
  now()-interval '2 days',now()-interval '2 days',null,null,'SSE HD-MOBILE-025',false),
 ('fd-mobile-payment-partial-045','fd-mobile-invoice-045',300000,
  'CASH','SUCCESS','DEMO-MOBILE-CASH-045',
  'Đã thu một phần bằng tiền mặt; phụ huynh còn số dư phải thanh toán.',
  now()-interval '3 days',now()-interval '3 days',now()-interval '3 days',
  null,null,false)
ON CONFLICT (id) DO UPDATE SET
    invoice_id=excluded.invoice_id,amount=excluded.amount,method=excluded.method,
    status=excluded.status,txn_ref=excluded.txn_ref,note=excluded.note,
    updated_at=excluded.updated_at,paid_at=excluded.paid_at,
    bank_transfer_content=excluded.bank_transfer_content,
    auto_provisioned=excluded.auto_provisioned;

INSERT INTO payment_receipts (
    id,amount,generation_attempts,invoice_code,invoice_id,issued_at,issued_by,
    method,parent_id,payment_id,receipt_number,status,student_code,student_id,
    student_name,revision,file_id,generated_at,generation_error
)
VALUES
 ('fd-mobile-receipt-045',300000,1,'HD-MOBILE-045','fd-mobile-invoice-045',
  now()-interval '3 days','fd-admin-001','CASH','fd-parent-033',
  'fd-mobile-payment-partial-045','PT-MOBILE-045','FAILED','HS270045',
  'fd-student-045','Học sinh Demo 045',1,null,null,
  'Chưa tạo file PDF vì dịch vụ lưu trữ chưa được bật.')
ON CONFLICT (id) DO UPDATE SET
    amount=excluded.amount,invoice_id=excluded.invoice_id,parent_id=excluded.parent_id,
    payment_id=excluded.payment_id,status='FAILED',student_id=excluded.student_id,
    revision=1,file_id=null,generated_at=null,
    generation_error=excluded.generation_error;

-- ---------------------------------------------------------------------------
-- Notifications, chat and extracurricular registrations for all test users.
-- ---------------------------------------------------------------------------
INSERT INTO notifications (
    id,body,created_at,read,recipient_id,ref_id,ref_type,title,type,channel,
    sent_at,status,attempt_count,deep_link,group_key,read_at
)
VALUES
 ('fd-mobile-noti-teacher-001-exam','Bạn được phân công coi thi khối 11 và khối 12.',now()-interval '2 days',false,
  'fd-teacher-001','fd-mobile-exam-period-k11','exam','Lịch coi thi mới','EXAM','IN_APP',now()-interval '2 days','SENT',1,'/teacher/exams','fd-mobile-exam',null),
 ('fd-mobile-noti-teacher-002-assignment','Bài Ngữ văn 10A2 có bài nộp mới cần chấm.',now()-interval '1 day',false,
  'fd-teacher-002','fd-mobile-assignment-lit-10a2','assignment','Bài nộp mới','ASSIGNMENT','IN_APP',now()-interval '1 day','SENT',1,'/teacher/assignments','fd-mobile-assignment-lit',null),
 ('fd-mobile-noti-teacher-002-leave','Có đơn giải trình chuyên cần mới của lớp 10A2.',now()-interval '12 hours',true,
  'fd-teacher-002','fd-mobile-excuse-011','attendance','Đơn xin phép mới','ATTENDANCE','IN_APP',now()-interval '12 hours','SENT',1,'/teacher/attendance','fd-mobile-excuse',now()-interval '8 hours'),
 ('fd-mobile-noti-teacher-003-assignment','Bài Tiếng Anh 10A1 có bài đã nộp.',now()-interval '1 day',false,
  'fd-teacher-003','fd-mobile-assignment-eng-10a1','assignment','Bài nộp mới','ASSIGNMENT','IN_APP',now()-interval '1 day','SENT',1,'/teacher/assignments','fd-mobile-assignment-eng',null),
 ('fd-mobile-noti-teacher-003-leave','Phụ huynh lớp 11A1 vừa gửi giải trình đi muộn.',now()-interval '10 hours',false,
  'fd-teacher-003','fd-mobile-excuse-025','attendance','Giải trình chuyên cần','ATTENDANCE','IN_APP',now()-interval '10 hours','SENT',1,'/teacher/attendance','fd-mobile-excuse',null),
 ('fd-mobile-noti-student-021-assignment','Bài English 11 mới đã được phát hành.',now()-interval '4 days',false,
  'fd-student-021','fd-mobile-assignment-eng-11a1','assignment','Bài tập mới','ASSIGNMENT','IN_APP',now()-interval '4 days','SENT',1,'/student/assignments','fd-mobile-assignment-k11',null),
 ('fd-mobile-noti-student-021-exam','Lịch kiểm tra giữa HK1 khối 11 đã được công bố.',now()-interval '3 days',true,
  'fd-student-021','fd-mobile-exam-period-k11','exam','Lịch kiểm tra mới','EXAM','IN_APP',now()-interval '3 days','SENT',1,'/student/exams','fd-mobile-exam-k11',now()-interval '2 days'),
 ('fd-mobile-noti-student-041-assignment','Bài luyện thi English 12 đang quá hạn và vẫn cho phép nộp.',now()-interval '1 day',false,
  'fd-student-041','fd-mobile-assignment-eng-12a1','assignment','Bài tập cần hoàn thành','ASSIGNMENT','IN_APP',now()-interval '1 day','SENT',1,'/student/assignments','fd-mobile-assignment-k12',null),
 ('fd-mobile-noti-student-041-exam','Lịch kiểm tra giữa HK1 khối 12 đã được công bố.',now()-interval '3 days',false,
  'fd-student-041','fd-mobile-exam-period-k12','exam','Lịch kiểm tra mới','EXAM','IN_APP',now()-interval '3 days','SENT',1,'/student/exams','fd-mobile-exam-k12',null),
 ('fd-mobile-noti-parent-013-invoice','Hóa đơn HD-MOBILE-025 đang chờ thanh toán.',now()-interval '8 days',false,
  'fd-parent-013','fd-mobile-invoice-025','invoice','Hóa đơn mới','FINANCE','IN_APP',now()-interval '8 days','SENT',1,'/parent/finance','fd-mobile-invoice-025',null),
 ('fd-mobile-noti-parent-013-exam','Lịch kiểm tra của Học sinh Demo 025 đã được cập nhật.',now()-interval '3 days',true,
  'fd-parent-013','fd-mobile-exam-period-k11','exam','Lịch kiểm tra của con','EXAM','IN_APP',now()-interval '3 days','SENT',1,'/parent/exams','fd-mobile-parent-exam-k11',now()-interval '2 days'),
 ('fd-mobile-noti-parent-013-attendance','Học sinh Demo 025 đi học muộn 15 phút.',now()-interval '3 days',false,
  'fd-parent-013','fd-mobile-attendance-025','attendance','Cảnh báo chuyên cần','ATTENDANCE','IN_APP',now()-interval '3 days','SENT',1,'/parent/attendance','fd-mobile-attendance-025',null),
 ('fd-mobile-noti-parent-033-invoice','Hóa đơn HD-MOBILE-045 đã thanh toán một phần, còn 350.000 đồng.',now()-interval '3 days',false,
  'fd-parent-033','fd-mobile-invoice-045','invoice','Hóa đơn còn số dư','FINANCE','IN_APP',now()-interval '3 days','SENT',1,'/parent/finance','fd-mobile-invoice-045',null),
 ('fd-mobile-noti-parent-033-exam','Lịch kiểm tra của Học sinh Demo 045 đã được cập nhật.',now()-interval '3 days',false,
  'fd-parent-033','fd-mobile-exam-period-k12','exam','Lịch kiểm tra của con','EXAM','IN_APP',now()-interval '3 days','SENT',1,'/parent/exams','fd-mobile-parent-exam-k12',null),
 ('fd-mobile-noti-parent-033-attendance','Đơn xin phép của Học sinh Demo 045 đã được duyệt.',now()-interval '6 hours',true,
  'fd-parent-033','fd-mobile-excuse-045','attendance','Đã duyệt giải trình','ATTENDANCE','IN_APP',now()-interval '6 hours','SENT',1,'/parent/attendance','fd-mobile-excuse-045',now()-interval '5 hours')
ON CONFLICT (id) DO UPDATE SET
    body=excluded.body,read=excluded.read,recipient_id=excluded.recipient_id,
    ref_id=excluded.ref_id,ref_type=excluded.ref_type,title=excluded.title,
    type=excluded.type,channel=excluded.channel,sent_at=excluded.sent_at,
    status=excluded.status,attempt_count=excluded.attempt_count,
    deep_link=excluded.deep_link,group_key=excluded.group_key,read_at=excluded.read_at;

INSERT INTO notification_delivery_logs (
    id,attempt_no,attempted_at,notification_id,provider_response,status,channel,provider
)
SELECT n.id||'-delivery',1,n.sent_at,n.id,'Full Demo Mobile in-app delivery',
       'SENT','IN_APP','LOCAL'
FROM notifications n WHERE n.id LIKE 'fd-mobile-noti-%'
ON CONFLICT (id) DO UPDATE SET
    attempted_at=excluded.attempted_at,provider_response=excluded.provider_response,
    status='SENT',channel='IN_APP',provider='LOCAL';

INSERT INTO chat_messages (
    id,body,created_at,read_flag,recipient_id,recipient_name,sender_id,sender_name
)
VALUES
 ('fd-mobile-chat-s1-t1-1','Thầy cho em hỏi phần nhận xét bài Toán vừa chấm.',now()-interval '2 days',true,
  'fd-teacher-001','Giáo viên Toán 1','fd-student-001','Học sinh Demo 001'),
 ('fd-mobile-chat-s1-t1-2','Em xem chi tiết trong bài nộp; thầy đã ghi rõ phần cần sửa.',now()-interval '1 day 20 hours',false,
  'fd-student-001','Học sinh Demo 001','fd-teacher-001','Giáo viên Toán 1'),
 ('fd-mobile-chat-t2-p6-1','Chào cô, gia đình đã gửi lý do nghỉ của học sinh 011.',now()-interval '2 days',true,
  'fd-teacher-002','Giáo viên Ngữ văn 1','fd-parent-006','Phụ huynh Demo 006'),
 ('fd-mobile-chat-t2-p6-2','Tôi đã nhận được và sẽ kiểm tra đơn trên hệ thống.',now()-interval '1 day 20 hours',false,
  'fd-parent-006','Phụ huynh Demo 006','fd-teacher-002','Giáo viên Ngữ văn 1'),
 ('fd-mobile-chat-t3-p13-1','Tôi muốn trao đổi về việc đi muộn của cháu.',now()-interval '2 days',true,
  'fd-teacher-003','Giáo viên Tiếng Anh 1','fd-parent-013','Phụ huynh Demo 013'),
 ('fd-mobile-chat-t3-p13-2','Tôi đã ghi nhận; phụ huynh vui lòng theo dõi phản hồi đơn xin phép.',now()-interval '1 day 18 hours',true,
  'fd-parent-013','Phụ huynh Demo 013','fd-teacher-003','Giáo viên Tiếng Anh 1'),
 ('fd-mobile-chat-t3-p13-3','Cảm ơn cô, tôi sẽ theo dõi trên ứng dụng.',now()-interval '1 day',false,
  'fd-teacher-003','Giáo viên Tiếng Anh 1','fd-parent-013','Phụ huynh Demo 013'),
 ('fd-mobile-chat-t5-p33-1','Gia đình đã bổ sung xác nhận nghỉ học của cháu 045.',now()-interval '1 day',true,
  'fd-teacher-005','Giáo viên Hóa học 1','fd-parent-033','Phụ huynh Demo 033'),
 ('fd-mobile-chat-t5-p33-2','Đơn đã được duyệt, phụ huynh có thể kiểm tra trong mục Chuyên cần.',now()-interval '10 hours',false,
  'fd-parent-033','Phụ huynh Demo 033','fd-teacher-005','Giáo viên Hóa học 1'),
 ('fd-mobile-chat-s21-t15-1','Thầy/cô cho em hỏi phần nhận xét bài English 11.',now()-interval '1 day',true,
  'fd-teacher-015','Giáo viên Tiếng Anh 2','fd-student-021','Học sinh Demo 021'),
 ('fd-mobile-chat-s21-t15-2','Em xem phần phản hồi trong bài nộp; nếu chưa rõ hãy nhắn lại.',now()-interval '8 hours',false,
  'fd-student-021','Học sinh Demo 021','fd-teacher-015','Giáo viên Tiếng Anh 2'),
 ('fd-mobile-chat-s41-t27-1','Em xin hỏi về bài luyện thi đã quá hạn.',now()-interval '12 hours',true,
  'fd-teacher-027','Giáo viên Tiếng Anh 3','fd-student-041','Học sinh Demo 041'),
 ('fd-mobile-chat-s41-t27-2','Bài vẫn cho phép nộp muộn, em hoàn thành trước tối nay.',now()-interval '6 hours',false,
  'fd-student-041','Học sinh Demo 041','fd-teacher-027','Giáo viên Tiếng Anh 3')
ON CONFLICT (id) DO UPDATE SET
    body=excluded.body,created_at=excluded.created_at,read_flag=excluded.read_flag,
    recipient_id=excluded.recipient_id,recipient_name=excluded.recipient_name,
    sender_id=excluded.sender_id,sender_name=excluded.sender_name;

INSERT INTO club_registrations (
    id,club_id,club_name,registered_at,registered_by,status,student_id,student_name
)
VALUES
 ('fd-mobile-club-021','fd-club-free','Câu lạc bộ Đọc sách',now()-interval '4 days',
  'fd-student-021','REGISTERED','fd-student-021','Học sinh Demo 021'),
 ('fd-mobile-club-041','fd-club-free','Câu lạc bộ Đọc sách',now()-interval '3 days',
  'fd-student-041','REGISTERED','fd-student-041','Học sinh Demo 041'),
 ('fd-mobile-club-025','fd-club-free','Câu lạc bộ Đọc sách',now()-interval '2 days',
  'fd-parent-013','REGISTERED','fd-student-025','Học sinh Demo 025'),
 ('fd-mobile-club-045','fd-club-free','Câu lạc bộ Đọc sách',now()-interval '1 day',
  'fd-parent-033','REGISTERED','fd-student-045','Học sinh Demo 045')
ON CONFLICT (id) DO UPDATE SET
    club_id=excluded.club_id,club_name=excluded.club_name,
    registered_at=excluded.registered_at,registered_by=excluded.registered_by,
    status='REGISTERED',student_id=excluded.student_id,student_name=excluded.student_name;

INSERT INTO audit_logs (
    id,action,actor_id,actor_name,created_at,detail,entity_id,entity_type,module,role,
    request_id,before_data,after_data
)
VALUES
 ('fd-mobile-audit-grade-002','GRADE_UPDATED','fd-teacher-002','Giáo viên Ngữ văn 1',now()-interval '2 days',
  'Sửa điểm Ngữ văn sau khi đối chiếu phiếu chấm.','fd-grade-011-lit-final','grade','academic','TEACHER',
  'fd-mobile-request-grade-002','{"score":7.4}'::jsonb,'{"score":8.4,"reason":"Đối chiếu phiếu chấm"}'::jsonb),
 ('fd-mobile-audit-grade-003','GRADE_UPDATED','fd-teacher-003','Giáo viên Tiếng Anh 1',now()-interval '2 days',
  'Sửa điểm Tiếng Anh theo biên bản chấm lại.','fd-grade-001-eng-mid','grade','academic','TEACHER',
  'fd-mobile-request-grade-003','{"score":8.4}'::jsonb,'{"score":8.8,"reason":"Chấm lại phần viết"}'::jsonb),
 ('fd-mobile-audit-payment-045','PAYMENT_CONFIRMED','fd-admin-002','Quản trị Demo 02',now()-interval '3 days',
  'Xác nhận thu tiền mặt một phần hóa đơn HD-MOBILE-045.','fd-mobile-payment-partial-045','payment','finance','ADMIN',
  'fd-mobile-request-payment-045','{"status":"PENDING"}'::jsonb,'{"status":"SUCCESS","amount":300000}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
    action=excluded.action,actor_id=excluded.actor_id,actor_name=excluded.actor_name,
    created_at=excluded.created_at,detail=excluded.detail,entity_id=excluded.entity_id,
    entity_type=excluded.entity_type,module=excluded.module,role=excluded.role,
    request_id=excluded.request_id,before_data=excluded.before_data,
    after_data=excluded.after_data;

-- Fail the transaction if any representative Mobile scope is still empty.
DO $$
DECLARE missing text;
BEGIN
    SELECT string_agg(label, ', ') INTO missing
    FROM (VALUES
      ('teacher-001 assignments', (SELECT count(*) FROM assignments WHERE teacher_id='fd-teacher-001')),
      ('teacher-002 assignments', (SELECT count(*) FROM assignments WHERE teacher_id='fd-teacher-002')),
      ('teacher-003 assignments', (SELECT count(*) FROM assignments WHERE teacher_id='fd-teacher-003')),
      ('teacher-001 exams', (SELECT count(*) FROM exam_room_assignments WHERE primary_proctor_id='fd-teacher-001' OR backup_proctor_id='fd-teacher-001')),
      ('teacher-002 exams', (SELECT count(*) FROM exam_room_assignments WHERE primary_proctor_id='fd-teacher-002' OR backup_proctor_id='fd-teacher-002')),
      ('teacher-003 exams', (SELECT count(*) FROM exam_room_assignments WHERE primary_proctor_id='fd-teacher-003' OR backup_proctor_id='fd-teacher-003')),
      ('student-001 assignments', (SELECT count(*) FROM assignments WHERE class_id='fd-class-10a1' AND status='PUBLISHED')),
      ('student-021 assignments', (SELECT count(*) FROM assignments WHERE class_id='fd-class-11a1' AND status='PUBLISHED')),
      ('student-041 assignments', (SELECT count(*) FROM assignments WHERE class_id='fd-class-12a1' AND status='PUBLISHED')),
      ('student-001 excuse', (SELECT count(*) FROM attendance_excuse_requests WHERE student_id='fd-student-001')),
      ('student-021 excuse', (SELECT count(*) FROM attendance_excuse_requests WHERE student_id='fd-student-021')),
      ('student-041 excuse', (SELECT count(*) FROM attendance_excuse_requests WHERE student_id='fd-student-041')),
      ('student-021 exams', (SELECT count(*) FROM exam_room_students WHERE student_id='fd-student-021')),
      ('student-041 exams', (SELECT count(*) FROM exam_room_students WHERE student_id='fd-student-041')),
      ('parent-001 invoices', (SELECT count(*) FROM invoices WHERE parent_id='fd-parent-001')),
      ('parent-013 invoices', (SELECT count(*) FROM invoices WHERE parent_id='fd-parent-013')),
      ('parent-033 invoices', (SELECT count(*) FROM invoices WHERE parent_id='fd-parent-033')),
      ('student-001 chat', (SELECT count(*) FROM chat_messages WHERE sender_id='fd-student-001' OR recipient_id='fd-student-001')),
      ('student-021 chat', (SELECT count(*) FROM chat_messages WHERE sender_id='fd-student-021' OR recipient_id='fd-student-021')),
      ('student-041 chat', (SELECT count(*) FROM chat_messages WHERE sender_id='fd-student-041' OR recipient_id='fd-student-041')),
      ('parent-013 chat', (SELECT count(*) FROM chat_messages WHERE sender_id='fd-parent-013' OR recipient_id='fd-parent-013')),
      ('parent-033 chat', (SELECT count(*) FROM chat_messages WHERE sender_id='fd-parent-033' OR recipient_id='fd-parent-033'))
    ) checks(label,total)
    WHERE total = 0;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Full Demo Mobile validation failed: %', missing;
    END IF;
END $$;

COMMIT;
