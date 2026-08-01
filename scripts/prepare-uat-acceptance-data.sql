\set ON_ERROR_STOP on
SET client_encoding TO 'UTF8';

BEGIN;

-- Hạnh kiểm được gắn đúng GVCN; điểm tổng kết vẫn do YearEndService tính lại.
UPDATE student_yearly_summaries summary
SET conduct_grade=CASE WHEN right(summary.student_id,1) IN ('0','5') THEN 'FAIR' ELSE 'GOOD' END,
    conduct_note='Đánh giá rèn luyện phục vụ nghiệm thu cuối năm',
    conduct_updated_by=classroom.homeroom_teacher_id,
    updated_at=now()
FROM classes classroom
WHERE classroom.id=summary.class_id AND summary.academic_year_id='ay-2026';

-- Lịch học kỳ 2 đầy đủ, độc lập với phiên bản đã phát hành của học kỳ 1.
DELETE FROM timetable_slots WHERE semester_id='sm-2026-2';
INSERT INTO timetable_slots
    (id,class_id,subject_id,subject_name,teacher_id,teacher_name,room_code,day_of_week,
     period_no,start_time,end_time,semester_id,published_plan_id,locked)
SELECT 'uat-hk2-'||class_id||'-'||day_of_week||'-'||period_no,
       class_id,subject_id,subject_name,teacher_id,teacher_name,room_code,day_of_week,
       period_no,start_time,end_time,'sm-2026-2',NULL,locked
FROM timetable_slots WHERE semester_id='sm-2026-1';

-- Mỗi học sinh đầu cấp có một phụ huynh để thử đủ quy trình nhập học và phân lớp.
INSERT INTO users
    (id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version)
SELECT 'u-intake-parent-'||n,'ph.daucap'||lpad(n::text,2,'0'),template.password_hash,
       'Phụ huynh đầu cấp '||lpad(n::text,2,'0'),
       'ph.daucap'||lpad(n::text,2,'0')||'@gmail.com','0966'||lpad(n::text,6,'0'),
       'PARENT','ACTIVE',now(),false,0
FROM generate_series(1,18) n
CROSS JOIN (SELECT password_hash FROM users WHERE username='ph.nguyenvanhung') template
ON CONFLICT (id) DO UPDATE SET
    full_name=excluded.full_name,email=excluded.email,phone=excluded.phone,status='ACTIVE';

INSERT INTO parent_student(id,parent_id,student_id,primary_contact)
SELECT 'ps-intake-'||n,'u-intake-parent-'||n,'u-intake-'||n,true
FROM generate_series(1,18) n
ON CONFLICT (id) DO NOTHING;

-- Hóa đơn bảo hiểm để nghiệm thu lọc theo đợt thu, lớp và trạng thái.
WITH students AS (
  SELECT id,full_name,class_id,class_name,row_number() OVER(ORDER BY id) rn
  FROM users
  WHERE role='STUDENT' AND class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')
)
INSERT INTO invoices
    (id,code,fee_period_id,student_id,student_name,parent_id,total_amount,paid_amount,status,
     due_date,issued_at,version,class_id,class_code,grade_level)
SELECT 'inv-bhyt-'||rn,'BHYT26-'||lpad(rn::text,4,'0'),'fee-26-2',s.id,s.full_name,
       (SELECT min(ps.parent_id) FROM parent_student ps WHERE ps.student_id=s.id),680000,
       CASE WHEN rn%3=0 THEN 680000 ELSE 0 END,
       CASE WHEN rn%3=0 THEN 'PAID' WHEN rn%3=1 THEN 'ISSUED' ELSE 'OVERDUE' END,
       date '2026-09-30',now(),0,s.class_id,s.class_name,left(s.class_name,2)
FROM students s
ON CONFLICT (id) DO UPDATE SET
    student_name=excluded.student_name,parent_id=excluded.parent_id,paid_amount=excluded.paid_amount,
    status=excluded.status,class_code=excluded.class_code,grade_level=excluded.grade_level;

INSERT INTO invoice_items(id,invoice_id,name,amount)
SELECT 'ii-'||id,id,'Bảo hiểm y tế năm học 2026-2027',680000
FROM invoices WHERE fee_period_id='fee-26-2'
ON CONFLICT (id) DO UPDATE SET name=excluded.name,amount=excluded.amount;

INSERT INTO payments
    (id,invoice_id,amount,method,status,txn_ref,receipt_code,payer_name,note,recorded_by,created_at,paid_at)
SELECT 'pay-'||id,id,paid_amount,'VIETQR','SUCCESS','VQR-'||code,'PT-'||code,
       'Phụ huynh học sinh','Thanh toán bảo hiểm qua VietQR','u-accountant-1',now(),now()
FROM invoices WHERE fee_period_id='fee-26-2' AND paid_amount>0
ON CONFLICT (id) DO UPDATE SET amount=excluded.amount,status='SUCCESS',note=excluded.note;

-- Điểm danh mẫu ở học kỳ 2 để báo cáo năm có dữ liệu ở cả hai giai đoạn.
WITH students AS (
  SELECT id,class_id,row_number() OVER(PARTITION BY class_id ORDER BY id) rn
  FROM users WHERE role='STUDENT' AND class_id IS NOT NULL
), first_slot AS (
  SELECT DISTINCT ON (class_id) id,class_id,subject_name,period_no
  FROM timetable_slots WHERE semester_id='sm-2026-2'
  ORDER BY class_id,day_of_week,period_no
)
INSERT INTO attendance_records
    (id,student_id,class_id,slot_id,date,status,note,subject_name,period_no,version,updated_at,updated_by)
SELECT 'att-hk2-'||s.id,s.id,s.class_id,t.id,date '2027-02-22',
       CASE s.rn%5 WHEN 0 THEN 'ABSENT_EXCUSED' WHEN 3 THEN 'LATE' ELSE 'PRESENT' END,
       CASE s.rn%5 WHEN 0 THEN 'Đã có đơn xin nghỉ được duyệt' WHEN 3 THEN 'Đi học muộn 5 phút' ELSE NULL END,
       t.subject_name,t.period_no,0,now(),c.homeroom_teacher_id
FROM students s JOIN first_slot t ON t.class_id=s.class_id JOIN classes c ON c.id=s.class_id
ON CONFLICT (student_id,slot_id,date) DO UPDATE SET status=excluded.status,note=excluded.note,updated_at=excluded.updated_at;

COMMIT;
