\set ON_ERROR_STOP on
SET client_encoding TO 'UTF8';

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Xóa vĩnh viễn toàn bộ dữ liệu hiện tại; chỉ giữ lịch sử migration để bảo toàn schema.
DO $$
DECLARE targets text;
BEGIN
  SELECT string_agg(format('%I.%I', schemaname, tablename), ', ' ORDER BY tablename)
  INTO targets
  FROM pg_tables
  WHERE schemaname='public' AND tablename <> 'flyway_schema_history';
  EXECUTE 'TRUNCATE TABLE ' || targets || ' CASCADE';
END $$;

-- Tái tạo tài khoản vận hành cốt lõi, không tái sử dụng bản ghi cũ.
INSERT INTO users(id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version) VALUES
('admin-1','admin','$2a$10$cMlNQ8ShHmqaMTxE611/OOfZAembbQKXMYVfKGyRzF4RyQiQxxm92','Bùi Đức Trung','admin@school.example','0000000001','ADMIN','ACTIVE',now(),false,0),
('academic-1','giaovu','$2a$10$.aBbRW8tfpzyFs/SVhyFAeLsezg1vDWEeuJDoBNPPVIC3CfDgb6Ii','Nguyễn Thu Hà','giaovu@school.example','0000000002','ACADEMIC_STAFF','ACTIVE',now(),false,0),
('accountant-1','ketoan','$2a$10$9xcG6FTAHQngp2VGBDws4OS/5ekMzdLGRYSzZiWQPg4itXXHvDMz.','Trần Ngọc Mai','ketoan@school.example','0000000003','ACCOUNTANT','ACTIVE',now(),false,0);

-- Danh mục 12 môn và bốn đầu điểm.
INSERT INTO subjects(id,code,name,coefficient,status) VALUES
('sub-math','MATH','Toán',1,'ACTIVE'),('sub-lit','LIT','Ngữ văn',1,'ACTIVE'),
('sub-eng','ENG','Tiếng Anh',1,'ACTIVE'),('sub-phys','PHYS','Vật lý',1,'ACTIVE'),
('sub-chem','CHEM','Hóa học',1,'ACTIVE'),('sub-bio','BIO','Sinh học',1,'ACTIVE'),
('sub-hist','HIST','Lịch sử',1,'ACTIVE'),('sub-geo','GEO','Địa lý',1,'ACTIVE'),
('sub-it','IT','Tin học',1,'ACTIVE'),('sub-tech','TECH','Công nghệ',1,'ACTIVE'),
('sub-pe','PE','Giáo dục thể chất',1,'ACTIVE'),('sub-civic','CIVIC','Giáo dục KT&PL',1,'ACTIVE');

INSERT INTO exam_categories(id,code,name,weight,required_count) VALUES
('cat-oral','ORAL','Kiểm tra miệng',1,1),('cat-15m','15M','Kiểm tra 15 phút',1,1),
('cat-mid','MID','Giữa học kỳ',2,1),('cat-final','FINAL','Cuối học kỳ',3,1);

-- 30 phòng học chính và 6 phòng chức năng.
INSERT INTO rooms(id,code,name,capacity,supports_morning,supports_afternoon,status,room_type,equipment_tags,home_room_eligible,notes)
SELECT 'room-main-'||lpad(n::text,2,'0'),'A'||lpad(n::text,3,'0'),'Phòng học A'||lpad(n::text,3,'0'),45,
  true,true,'ACTIVE','HOMEROOM','Máy chiếu, điều hòa, bảng thông minh',true,'Phòng học chính'
FROM generate_series(1,30) n;
INSERT INTO rooms(id,code,name,capacity,supports_morning,supports_afternoon,status,room_type,equipment_tags,home_room_eligible,notes) VALUES
('room-lab-phys','LAB-LY','Phòng thí nghiệm Vật lý',36,true,true,'ACTIVE','FUNCTIONAL','Bộ thí nghiệm Vật lý',false,'Chỉ dùng theo tiết'),
('room-lab-chem','LAB-HOA','Phòng thí nghiệm Hóa học',36,true,true,'ACTIVE','FUNCTIONAL','Tủ hóa chất, bàn thí nghiệm',false,'Chỉ dùng theo tiết'),
('room-lab-bio','LAB-SINH','Phòng thí nghiệm Sinh học',36,true,true,'ACTIVE','FUNCTIONAL','Kính hiển vi',false,'Chỉ dùng theo tiết'),
('room-computer','LAB-TIN','Phòng máy tính',40,true,true,'ACTIVE','FUNCTIONAL','40 máy tính',false,'Chỉ dùng theo tiết'),
('room-language','LAB-NN','Phòng học ngoại ngữ',40,true,true,'ACTIVE','FUNCTIONAL','Tai nghe, màn hình',false,'Chỉ dùng theo tiết'),
('room-hall','HT-01','Hội trường',300,true,true,'ACTIVE','FUNCTIONAL','Âm thanh, máy chiếu',false,'Sự kiện toàn trường');

-- Một năm học lịch sử đã đóng và một năm học hiện hành.
INSERT INTO academic_years(id,code,name,start_date,end_date,status,orientation_start_date,opening_date,instruction_weeks,auto_generated) VALUES
('ay-2025','2025-2026','Năm học 2025-2026',date '2025-08-18',date '2026-05-31','CLOSED',date '2025-08-11',date '2025-09-05',35,false),
('ay-2026','2026-2027','Năm học 2026-2027',date '2026-08-01',date '2027-05-31','ACTIVE',date '2026-08-01',date '2026-09-05',35,false);
INSERT INTO semesters(id,academic_year_id,code,name,sequence,start_date,end_date,status,instruction_weeks,auto_generated) VALUES
('sem-2025-1','ay-2025','HK1','Học kỳ 1',1,date '2025-08-18',date '2026-01-11','CLOSED',18,true),
('sem-2025-2','ay-2025','HK2','Học kỳ 2',2,date '2026-01-12',date '2026-05-31','CLOSED',17,true),
('sem-2026-1','ay-2026','HK1','Học kỳ 1',1,date '2026-08-01',date '2027-01-15','ACTIVE',18,true),
('sem-2026-2','ay-2026','HK2','Học kỳ 2',2,date '2027-01-18',date '2027-05-31','PLANNED',17,true);
INSERT INTO cohorts(id,code,name,entry_year,graduation_year,duration_years,status,entry_academic_year_id,created_at,created_by,completed_at) VALUES
('cohort-2023','2023-2026','Niên khóa 2023-2026',2023,2026,3,'COMPLETED','ay-2025',now(),'admin-1',timestamp with time zone '2026-06-15 08:00:00+07'),
('cohort-2024','2024-2027','Niên khóa 2024-2027',2024,2027,3,'ACTIVE','ay-2025',now(),'admin-1',NULL),
('cohort-2025','2025-2028','Niên khóa 2025-2028',2025,2028,3,'ACTIVE','ay-2025',now(),'admin-1',NULL),
('cohort-2026','2026-2029','Niên khóa 2026-2029',2026,2029,3,'ACTIVE','ay-2026',now(),'admin-1',NULL);

-- 72 giáo viên: sáu giáo viên cho mỗi bộ môn.
WITH teacher_source AS (
  SELECT n,ceil(n/6.0)::int subject_no,((n-1)%6)+1 variant,
    (ARRAY['sub-math','sub-lit','sub-eng','sub-phys','sub-chem','sub-bio','sub-hist','sub-geo','sub-it','sub-tech','sub-pe','sub-civic'])[ceil(n/6.0)::int] subject_id,
    (ARRAY['Toán','Ngữ văn','Tiếng Anh','Vật lý','Hóa học','Sinh học','Lịch sử','Địa lý','Tin học','Công nghệ','Giáo dục thể chất','Giáo dục KT&PL'])[ceil(n/6.0)::int] subject_name,
    (ARRAY['toan','nguvan','tienganh','vatly','hoahoc','sinhhoc','lichsu','dialy','tinhoc','congnghe','thechat','ktpl'])[ceil(n/6.0)::int] subject_slug
  FROM generate_series(1,72) n
), named AS (
  SELECT *,CASE WHEN n=1 THEN 'Nguyễn Đức Minh' ELSE
    (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Vũ','Đặng','Đỗ','Bùi','Ngô','Dương','Đinh'])[((n-1)%12)+1]||' '||
    (ARRAY['Văn','Thị','Đức','Quốc','Minh','Ngọc'])[((n-1)/12)%6+1]||' '||
    (ARRAY['Anh','Bình','Châu','Dũng','Giang','Hà','Hải','Hạnh','Hùng','Hương','Khánh','Lan','Linh','Long','Mai','Nam','Phương','Quân'])[((n-1)/6)%18+1]
  END full_name FROM teacher_source
)
INSERT INTO users(id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version,teacher_code,main_subject,main_subject_id)
SELECT 'teacher-'||lpad(n::text,3,'0'),CASE WHEN n=1 THEN 'gv.nguyenminh' ELSE 'gv.'||subject_slug||lpad(variant::text,2,'0') END,
  CASE WHEN n=1 THEN '$2a$10$Vav5RTJMnCL8hNOz6bfDcOYH8iuaaDQ6X3uBbYu6adItFdfAL0toK'
       ELSE crypt('GV@2026'||lpad(n::text,3,'0')||'Aa',gen_salt('bf',10)) END,
  full_name,CASE WHEN n=1 THEN 'gv.nguyenminh@school.example' ELSE 'gv.'||subject_slug||lpad(variant::text,2,'0')||'@school.example' END,
  '0001'||lpad(n::text,6,'0'),'TEACHER','ACTIVE',now(),n<>1,0,'GV'||lpad(n::text,3,'0'),subject_name,subject_id
FROM named;

-- Giáo viên Toán lưu hồ sơ lịch sử để tài khoản UAT chỉ nhận phạm vi năm học hiện hành.
INSERT INTO users(id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version,teacher_code,main_subject,main_subject_id)
VALUES ('teacher-073','gv.toanlichsu01',crypt('GV@2026073Aa',gen_salt('bf',10)),'Lê Minh Khôi','gv.toanlichsu01@school.example',
  '0001000073','TEACHER','ACTIVE',now(),true,0,'GV073','Toán','sub-math');

-- 36 lớp mỗi năm học, đủ ba khối, mỗi khối mười hai lớp.
WITH class_source AS (
  SELECT n,10+((n-1)/12)::int grade,((n-1)%12)+1 section FROM generate_series(1,36) n
), years AS (SELECT * FROM (VALUES('old','ay-2025'),('current','ay-2026')) y(prefix,year_id))
INSERT INTO classes(id,academic_year_id,code,name,grade_level,homeroom_teacher_id,homeroom_teacher_name,
  homeroom_assigned_at,homeroom_assigned_by,student_count,capacity,study_shift,room_id,room_code,status,cohort_id,planned_student_count,auto_generated)
SELECT 'class-'||y.prefix||'-'||s.grade||'a'||s.section,y.year_id,s.grade||'A'||s.section,'Lớp '||s.grade||'A'||s.section,
  'K'||s.grade,'teacher-'||lpad((CASE WHEN y.prefix='old' AND s.n=1 THEN 37 WHEN y.prefix='old' THEN s.n WHEN s.n=1 THEN 1 ELSE s.n+36 END)::text,3,'0'),u.full_name,
  now(),'admin-1',LEAST(42,GREATEST(0,500-(s.section-1)*42)),45,CASE WHEN s.section%2=1 THEN 'MORNING' ELSE 'AFTERNOON' END,
  'room-main-'||lpad((((s.grade-10)*6)+ceil(s.section/2.0))::int::text,2,'0'),
  'A'||lpad((((s.grade-10)*6)+ceil(s.section/2.0))::int::text,3,'0'),'ACTIVE',
  CASE WHEN y.prefix='old' THEN 'cohort-'||(2025-(s.grade-10)) ELSE 'cohort-'||(2026-(s.grade-10)) END,LEAST(42,GREATEST(0,500-(s.section-1)*42)),true
FROM class_source s CROSS JOIN years y
JOIN users u ON u.id='teacher-'||lpad((CASE WHEN y.prefix='old' AND s.n=1 THEN 37 WHEN y.prefix='old' THEN s.n WHEN s.n=1 THEN 1 ELSE s.n+36 END)::text,3,'0');

-- 2.000 học sinh thuộc bốn niên khóa; mỗi niên khóa có 500 học sinh.
WITH cohorts_data(cohort_year,cohort_id,old_grade,current_grade) AS (VALUES
  (2023,'cohort-2023',12,NULL::int),(2024,'cohort-2024',11,12),(2025,'cohort-2025',10,11),(2026,'cohort-2026',NULL::int,10)
), raw_students AS (
  SELECT c.*,n,ceil(n/42.0)::int section,(c.cohort_year-2023)*500+n person_no
  FROM cohorts_data c CROSS JOIN generate_series(1,500) n
), student_source AS (
  SELECT r.*,
    (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Vũ','Đặng','Đỗ','Bùi','Ngô','Dương','Đinh'])[((person_no-1)%12)+1]||' '||
    (ARRAY['Minh','Gia','Khánh','Bảo','Thanh','Hoài','Đức','Ngọc'])[((person_no-1)/12)%8+1]||' '||
    (ARRAY['Ân','Anh','Bình','Châu','Dũng','Giang','Hà','Hải','Hân','Hùng','Hương','Khang','Lan','Linh','Long','Mai','Nam','Ngân','Phúc','Phương','Quân','Thảo','Trang','Trung'])[((person_no-1)/96)%24+1] full_name
  FROM raw_students r
)
INSERT INTO users(id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version,
  student_code,class_id,class_name,cohort_id,student_status,date_of_birth,gender,place_of_birth,ethnicity,nationality,address,enrollment_date,
  guardian_name,guardian_phone,graduated_at,graduation_academic_year_id,graduation_class_id)
SELECT 'student-'||cohort_year||'-'||lpad(n::text,3,'0'),
  CASE WHEN cohort_year=2026 AND n=1 THEN 'hs.nguyenminhan' ELSE 'hs.'||cohort_year||'.'||lpad(n::text,3,'0') END,
  CASE WHEN cohort_year=2026 AND n=1 THEN '$2a$10$Y8J/yvtyHP83MNPQ.oikV.hTR3D1X2KNV5v3DwTFNFXSW3a7Gvk3.' ELSE crypt('HS@'||cohort_year||lpad(n::text,3,'0')||'Aa',gen_salt('bf',10)) END,
  CASE WHEN cohort_year=2026 AND n=1 THEN 'Nguyễn Minh An' ELSE full_name END,
  CASE WHEN cohort_year=2026 AND n=1 THEN 'hs.nguyenminhan@school.example' ELSE 'hs.'||cohort_year||'.'||lpad(n::text,3,'0')||'@school.example' END,
  '0002'||lpad(((cohort_year-2023)*500+n)::text,6,'0'),'STUDENT','ACTIVE',now(),NOT (cohort_year=2026 AND n=1),0,
  'HS'||right(cohort_year::text,2)||lpad(n::text,4,'0'),
  CASE WHEN current_grade IS NULL THEN NULL ELSE 'class-current-'||current_grade||'a'||section END,
  CASE WHEN current_grade IS NULL THEN NULL ELSE current_grade||'A'||section END,cohort_id,
  CASE WHEN current_grade IS NULL THEN 'GRADUATED' ELSE 'ENROLLED' END,
  make_date(cohort_year-16,((n-1)%12)+1,((n-1)%27)+1),CASE WHEN n%2=0 THEN 'FEMALE' ELSE 'MALE' END,
  'Hà Nội','Kinh','Việt Nam',(10+n)||' đường Học Đường, Hà Nội',make_date(cohort_year,8,15),
  'Phụ huynh của '||(CASE WHEN cohort_year=2026 AND n=1 THEN 'Nguyễn Minh An' ELSE full_name END),'0003'||lpad(((cohort_year-2023)*500+n)::text,6,'0'),
  CASE WHEN current_grade IS NULL THEN timestamp with time zone '2026-06-15 08:00:00+07' ELSE NULL END,
  CASE WHEN current_grade IS NULL THEN 'ay-2025' ELSE NULL END,
  CASE WHEN current_grade IS NULL THEN 'class-old-12a'||section ELSE NULL END
FROM student_source;

-- Lịch sử xếp lớp của năm cũ và năm hiện hành.
INSERT INTO class_enrollments(id,student_id,class_id,academic_year_id,status,enrolled_at,ended_at,cohort_id)
SELECT 'enroll-old-'||u.id,u.id,'class-old-'||d.old_grade||'a'||ceil(d.n/42.0)::int,'ay-2025','COMPLETED',
  timestamp with time zone '2025-08-18 07:00:00+07',timestamp with time zone '2026-05-31 17:00:00+07',u.cohort_id
FROM (SELECT * FROM users WHERE role='STUDENT' AND id LIKE 'student-202%') u
JOIN LATERAL (SELECT split_part(u.id,'-',2)::int cohort_year,split_part(u.id,'-',3)::int n) x ON true
JOIN (VALUES(2023,12),(2024,11),(2025,10)) g(cohort_year,old_grade) ON g.cohort_year=x.cohort_year
CROSS JOIN LATERAL (SELECT g.old_grade,x.n) d;
INSERT INTO class_enrollments(id,student_id,class_id,academic_year_id,status,enrolled_at,cohort_id)
SELECT 'enroll-current-'||u.id,u.id,u.class_id,'ay-2026','ACTIVE',timestamp with time zone '2026-08-01 07:00:00+07',u.cohort_id
FROM users u WHERE u.role='STUDENT' AND u.class_id LIKE 'class-current-%';

-- Một tài khoản phụ huynh cho mỗi học sinh và liên kết đầy đủ.
WITH student_order AS (SELECT u.*,row_number() over(order by CASE WHEN u.id='student-2026-001' THEN 0 ELSE 1 END,u.id) rn FROM users u WHERE role='STUDENT')
INSERT INTO users(id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version)
SELECT 'parent-'||lpad(rn::text,4,'0'),CASE WHEN rn=1 THEN 'ph.nguyenvanhung' ELSE 'ph.'||lpad(rn::text,4,'0') END,
  CASE WHEN rn=1 THEN '$2a$10$Ksdv13aesVO46/Nk1FDliuft1Ks.xRJnKAjmJboabxPIQWWi.bS66' ELSE crypt('PH@'||lpad(rn::text,4,'0')||'Aa2026',gen_salt('bf',10)) END,
  CASE WHEN rn=1 THEN 'Nguyễn Văn Hùng' ELSE
    (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Vũ','Đặng','Đỗ','Bùi','Ngô','Dương','Đinh'])[((rn+116)%12)+1]||' '||
    (ARRAY['Văn','Thị','Đức','Quốc','Minh','Ngọc','Thanh','Hoài'])[((rn+116)/12)%8+1]||' '||
    (ARRAY['An','Anh','Bình','Châu','Dũng','Giang','Hà','Hải','Hân','Hiếu','Hương','Khang','Lan','Linh','Long','Mai','Nam','Ngân','Phúc','Phương','Quân','Thảo','Trang','Trung'])[((rn+116)/96)%24+1] END,
  CASE WHEN rn=1 THEN 'ph.nguyenvanhung@parent.example' ELSE 'ph.'||lpad(rn::text,4,'0')||'@parent.example' END,
  '0004'||lpad(rn::text,6,'0'),'PARENT','ACTIVE',now(),rn<>1,0 FROM student_order;
WITH student_order AS (SELECT id,row_number() over(order by CASE WHEN id='student-2026-001' THEN 0 ELSE 1 END,id) rn FROM users WHERE role='STUDENT')
INSERT INTO parent_student(id,parent_id,student_id,primary_contact)
SELECT 'relation-'||lpad(rn::text,4,'0'),'parent-'||lpad(rn::text,4,'0'),id,true FROM student_order;

-- Guardian display data follows the actual linked parent instead of a generic
-- "Phụ huynh của ..." placeholder.
UPDATE users student
SET guardian_name = parent_user.full_name,
    guardian_phone = parent_user.phone
FROM parent_student relation
JOIN users parent_user ON parent_user.id = relation.parent_id
WHERE student.id = relation.student_id AND student.role = 'STUDENT';

-- Định mức chương trình, tải dạy và phân công đủ 12 môn.
WITH curriculum(subject_id,subject_name,weekly_periods,subject_no) AS (VALUES
('sub-math','Toán',4,1),('sub-lit','Ngữ văn',4,2),('sub-eng','Tiếng Anh',3,3),('sub-phys','Vật lý',3,4),
('sub-chem','Hóa học',2,5),('sub-bio','Sinh học',2,6),('sub-hist','Lịch sử',2,7),('sub-geo','Địa lý',2,8),
('sub-it','Tin học',2,9),('sub-tech','Công nghệ',2,10),('sub-pe','Giáo dục thể chất',2,11),('sub-civic','Giáo dục KT&PL',2,12)),
scope AS (SELECT s.id semester_id,g.grade_level FROM semesters s CROSS JOIN (VALUES('K10'),('K11'),('K12')) g(grade_level))
INSERT INTO curriculum_requirements(id,semester_id,grade_level,subject_id,subject_name,weekly_periods,created_at,updated_at)
SELECT 'curr-'||semester_id||'-'||grade_level||'-'||subject_id,semester_id,grade_level,subject_id,subject_name,weekly_periods,now(),now()
FROM scope CROSS JOIN curriculum;

INSERT INTO teacher_load_registrations(id,teacher_id,teacher_name,semester_id,max_weekly_periods,unavailable_slots,preferred_grade_levels,
  note,review_note,status,submitted_at,reviewed_at,reviewed_by,created_at,updated_at)
SELECT 'load-'||s.id||'-'||u.id,u.id,u.full_name,s.id,24,
  CASE (row_number() over(partition by s.id order by u.id))%6 WHEN 0 THEN 'MON:1' WHEN 1 THEN 'TUE:5' WHEN 2 THEN 'WED:1' WHEN 3 THEN 'THU:5' WHEN 4 THEN 'FRI:1' ELSE 'SAT:5' END,
  'K10,K11,K12','Đăng ký tải dạy theo học kỳ','Đã kiểm tra chuyên môn và tải dạy',
  CASE WHEN s.id='sem-2026-2' THEN 'SUBMITTED' ELSE 'APPROVED' END,now()-interval '20 days',
  CASE WHEN s.id='sem-2026-2' THEN NULL ELSE now()-interval '18 days' END,
  CASE WHEN s.id='sem-2026-2' THEN NULL ELSE 'academic-1' END,now(),now()
FROM users u CROSS JOIN semesters s WHERE u.role='TEACHER';

WITH curriculum(subject_id,subject_name,weekly_periods,subject_no) AS (VALUES
('sub-math','Toán',4,1),('sub-lit','Ngữ văn',4,2),('sub-eng','Tiếng Anh',3,3),('sub-phys','Vật lý',3,4),
('sub-chem','Hóa học',2,5),('sub-bio','Sinh học',2,6),('sub-hist','Lịch sử',2,7),('sub-geo','Địa lý',2,8),
('sub-it','Tin học',2,9),('sub-tech','Công nghệ',2,10),('sub-pe','Giáo dục thể chất',2,11),('sub-civic','Giáo dục KT&PL',2,12)),
class_scope AS (SELECT c.*,regexp_replace(c.code,'[^0-9]','','g')::int grade,substring(c.code from 'A([0-9]+)$')::int section FROM classes c),
semester_scope AS (SELECT s.*,CASE s.academic_year_id WHEN 'ay-2025' THEN 'old' ELSE 'current' END prefix FROM semesters s)
INSERT INTO teaching_assignments(id,class_id,class_code,semester_id,subject_id,subject_name,teacher_id,teacher_name,weekly_periods,
  assigned_at,assigned_by,updated_at,effective_from,effective_to,status,version)
SELECT 'assign-'||sem.id||'-'||c.code||'-'||cur.subject_id,c.id,c.code,sem.id,cur.subject_id,cur.subject_name,
  'teacher-'||lpad((CASE WHEN sem.prefix='old' AND cur.subject_no=1 AND c.section=1 THEN 73 ELSE ((cur.subject_no-1)*6)+((c.section-1)%6)+1 END)::text,3,'0'),u.full_name,cur.weekly_periods,now(),'academic-1',now(),sem.start_date,sem.end_date,'ACTIVE',0
FROM semester_scope sem JOIN class_scope c ON c.academic_year_id=sem.academic_year_id
CROSS JOIN curriculum cur JOIN users u ON u.id='teacher-'||lpad((CASE WHEN sem.prefix='old' AND cur.subject_no=1 AND c.section=1 THEN 73 ELSE ((cur.subject_no-1)*6)+((c.section-1)%6)+1 END)::text,3,'0');

-- Thời khóa biểu 30 tiết/tuần cho cả hai học kỳ cũ và HK1 hiện hành.
WITH curriculum(ord,subject_id,subject_name,weekly_periods) AS (VALUES
(1,'sub-math','Toán',4),(2,'sub-lit','Ngữ văn',4),(3,'sub-eng','Tiếng Anh',3),(4,'sub-phys','Vật lý',3),
(5,'sub-chem','Hóa học',2),(6,'sub-bio','Sinh học',2),(7,'sub-hist','Lịch sử',2),(8,'sub-geo','Địa lý',2),
(9,'sub-it','Tin học',2),(10,'sub-tech','Công nghệ',2),(11,'sub-pe','Giáo dục thể chất',2),(12,'sub-civic','Giáo dục KT&PL',2)),
expanded AS (SELECT c.*,g.n,row_number() over(order by c.ord,g.n)-1 base_slot FROM curriculum c CROSS JOIN LATERAL generate_series(1,c.weekly_periods) g(n)),
class_scope AS (SELECT c.*,regexp_replace(c.code,'[^0-9]','','g')::int grade,substring(c.code from 'A([0-9]+)$')::int section FROM classes c),
scope AS (SELECT s.*,CASE s.academic_year_id WHEN 'ay-2025' THEN 'old' ELSE 'current' END prefix FROM semesters s WHERE s.id<>'sem-2026-2'),
placed AS (SELECT sem.id semester_id,sem.prefix,c.*,x.subject_id,x.subject_name,x.ord,((x.base_slot+(c.grade-10)*10+((c.section-1)/6)*15)%30) slot_no
  FROM scope sem JOIN class_scope c ON c.academic_year_id=sem.academic_year_id CROSS JOIN expanded x)
INSERT INTO timetable_slots(id,class_id,subject_id,subject_name,teacher_id,teacher_name,room_code,day_of_week,period_no,start_time,end_time,semester_id,locked)
SELECT 'slot-'||p.semester_id||'-'||p.code||'-'||(p.slot_no+1),p.id,p.subject_id,p.subject_name,
  'teacher-'||lpad((CASE WHEN p.prefix='old' AND p.ord=1 AND p.section=1 THEN 73 ELSE ((p.ord-1)*6)+((p.section-1)%6)+1 END)::text,3,'0'),u.full_name,p.room_code,
  (ARRAY['MON','TUE','WED','THU','FRI','SAT'])[(p.slot_no/5)+1],(p.slot_no%5)+1,
  CASE p.study_shift WHEN 'AFTERNOON' THEN (ARRAY['13:00','13:50','14:50','15:40','16:35'])[(p.slot_no%5)+1] ELSE (ARRAY['07:00','07:50','08:50','09:40','10:35'])[(p.slot_no%5)+1] END,
  CASE p.study_shift WHEN 'AFTERNOON' THEN (ARRAY['13:45','14:35','15:35','16:25','17:20'])[(p.slot_no%5)+1] ELSE (ARRAY['07:45','08:35','09:35','10:25','11:20'])[(p.slot_no%5)+1] END,
  p.semester_id,false FROM placed p JOIN users u ON u.id='teacher-'||lpad((CASE WHEN p.prefix='old' AND p.ord=1 AND p.section=1 THEN 73 ELSE ((p.ord-1)*6)+((p.section-1)%6)+1 END)::text,3,'0');

-- Điểm đầy đủ của năm cũ và dữ liệu học tập đang phát sinh ở HK1 hiện hành.
WITH old_students AS (SELECT u.id,row_number() over(order by u.id) rn FROM users u JOIN class_enrollments e ON e.student_id=u.id AND e.academic_year_id='ay-2025'),
sub AS (SELECT id,name,row_number() over(order by id) sn FROM subjects),sem AS (SELECT id,sequence FROM semesters WHERE academic_year_id='ay-2025'),
cat(code,name,idx) AS (VALUES('ORAL','Kiểm tra miệng',1),('15M','Kiểm tra 15 phút',2),('MID','Giữa học kỳ',3),('FINAL','Cuối học kỳ',4))
INSERT INTO grades(id,student_id,subject_id,subject_name,semester_id,category,category_name,assessment_index,score,note,recorded_at,created_at,created_by,updated_at,updated_by,version)
SELECT 'grade-old-'||st.id||'-'||sem.id||'-'||sub.id||'-'||cat.code,st.id,sub.id,sub.name,sem.id,cat.code,cat.name,1,
  round((6.2+((st.rn+sub.sn+sem.sequence+cat.idx)%34)/10.0)::numeric,1),'Điểm chính thức năm học 2025-2026',now(),now(),
  'teacher-'||lpad((((sub.sn-1)*6)+1)::text,3,'0'),now(),'teacher-'||lpad((((sub.sn-1)*6)+1)::text,3,'0'),0
FROM old_students st CROSS JOIN sub CROSS JOIN sem CROSS JOIN cat;

WITH current_students AS (SELECT id,row_number() over(order by id) rn FROM users WHERE role='STUDENT' AND class_id LIKE 'class-current-%'),
sub AS (SELECT id,name,row_number() over(order by id) sn FROM subjects),
cat(code,name,idx) AS (VALUES('ORAL','Kiểm tra miệng',1),('15M','Kiểm tra 15 phút',2),('MID','Giữa học kỳ',3),('FINAL','Cuối học kỳ',4))
INSERT INTO grades(id,student_id,subject_id,subject_name,semester_id,category,category_name,assessment_index,score,note,recorded_at,created_at,created_by,updated_at,updated_by,version)
SELECT 'grade-current-'||st.id||'-'||sub.id||'-'||cat.code,st.id,sub.id,sub.name,'sem-2026-1',cat.code,cat.name,1,
  round((5.8+((st.rn+sub.sn+cat.idx)%40)/10.0)::numeric,1),'Dữ liệu học kỳ đang theo dõi',now(),now(),
  'teacher-'||lpad((((sub.sn-1)*6)+1)::text,3,'0'),now(),'teacher-'||lpad((((sub.sn-1)*6)+1)::text,3,'0'),0
FROM current_students st CROSS JOIN sub CROSS JOIN cat WHERE st.rn%5<>0 OR cat.code IN ('ORAL','15M');

-- Tổng kết lịch sử được tính từ đủ 12 môn của cả hai học kỳ, không chèn điểm trung bình giả.
WITH weighted_subjects AS (
  SELECT g.student_id,g.semester_id,g.subject_id,
    sum(g.score*CASE g.category WHEN 'MID' THEN 2 WHEN 'FINAL' THEN 3 ELSE 1 END)
      /sum(CASE g.category WHEN 'MID' THEN 2 WHEN 'FINAL' THEN 3 ELSE 1 END) subject_average
  FROM grades g JOIN semesters s ON s.id=g.semester_id AND s.academic_year_id='ay-2025'
  GROUP BY g.student_id,g.semester_id,g.subject_id
), semester_averages AS (
  SELECT student_id,semester_id,avg(subject_average) semester_average
  FROM weighted_subjects GROUP BY student_id,semester_id HAVING count(*)=12
), annual AS (
  SELECT student_id,
    max(semester_average) FILTER (WHERE semester_id='sem-2025-1') semester_one_average,
    max(semester_average) FILTER (WHERE semester_id='sem-2025-2') semester_two_average
  FROM semester_averages GROUP BY student_id HAVING count(*)=2
)
INSERT INTO student_yearly_summaries(id,academic_year_id,student_id,student_name,class_id,semester_one_average,semester_two_average,average_score,
  conduct_grade,conduct_note,promotion_status,missing_requirements,finalized_at,finalized_by,updated_at,conduct_updated_by,version)
SELECT 'summary-2025-'||u.id,'ay-2025',u.id,u.full_name,e.class_id,
  round(a.semester_one_average::numeric,2),round(a.semester_two_average::numeric,2),
  round(((a.semester_one_average+a.semester_two_average*2)/3)::numeric,2),
  CASE WHEN row_number() over(order by u.id)%20=0 THEN 'AVERAGE' ELSE 'GOOD' END,'Đã hoàn thành rèn luyện năm học 2025-2026',
  CASE WHEN c.grade_level='K12' THEN 'GRADUATED' ELSE 'PROMOTED' END,NULL,timestamp with time zone '2026-06-10 10:00:00+07','academic-1',now(),c.homeroom_teacher_id,0
FROM annual a JOIN users u ON u.id=a.student_id
JOIN class_enrollments e ON e.student_id=u.id AND e.academic_year_id='ay-2025' JOIN classes c ON c.id=e.class_id;

INSERT INTO report_cards(id,academic_year_id,student_id,class_id,homeroom_teacher_id,homeroom_comment,status,verification_code,
  submitted_at,submitted_by,approved_at,approved_by,locked_at,locked_by,published_at,published_by,created_at,updated_at,version)
SELECT 'report-card-'||u.id,'ay-2025',u.id,e.class_id,c.homeroom_teacher_id,'Em đã hoàn thành chương trình học tập và rèn luyện của năm học.',
  'PUBLISHED','HB25'||lpad(rn::text,8,'0'),
  timestamp with time zone '2026-06-08 09:00:00+07',c.homeroom_teacher_id,timestamp with time zone '2026-06-09 09:00:00+07','academic-1',
  timestamp with time zone '2026-06-10 09:00:00+07','academic-1',
  timestamp with time zone '2026-06-12 09:00:00+07','academic-1',now(),now(),0
FROM (SELECT u.*,row_number() over(order by u.id) rn FROM users u WHERE role='STUDENT') u
JOIN class_enrollments e ON e.student_id=u.id AND e.academic_year_id='ay-2025' JOIN classes c ON c.id=e.class_id;

-- Điểm danh, bài tập, đơn xin nghỉ và trao đổi của năm hiện hành.
WITH students AS (SELECT u.*,row_number() over(partition by u.class_id order by u.id) rn FROM users u WHERE role='STUDENT' AND class_id LIKE 'class-current-%'),
slots AS (SELECT DISTINCT ON (class_id) id,class_id,subject_name,period_no FROM timetable_slots WHERE semester_id='sem-2026-1' ORDER BY class_id,day_of_week,period_no),
days AS (SELECT generate_series(date '2026-08-03',date '2026-08-07',interval '1 day')::date school_day)
INSERT INTO attendance_records(id,student_id,class_id,slot_id,date,status,note,subject_name,period_no,version,updated_at,updated_by)
SELECT 'attendance-'||s.id||'-'||to_char(d.school_day,'YYYYMMDD'),s.id,s.class_id,t.id,d.school_day,
  CASE WHEN (s.rn+extract(day from d.school_day)::int)%23=0 THEN 'ABSENT_EXCUSED' WHEN (s.rn+extract(day from d.school_day)::int)%19=0 THEN 'LATE' ELSE 'PRESENT' END,
  CASE WHEN (s.rn+extract(day from d.school_day)::int)%23=0 THEN 'Phụ huynh đã xác nhận xin nghỉ' ELSE NULL END,t.subject_name,t.period_no,0,now(),'teacher-001'
FROM students s JOIN slots t ON t.class_id=s.class_id CROSS JOIN days d;

INSERT INTO assignments(id,title,description,class_id,subject_id,subject_name,teacher_id,teacher_name,deadline,allow_late,status,created_at,updated_at,version)
SELECT 'assignment-current-'||c.id,'Bài luyện tập đầu năm - '||c.code,'Ôn tập kiến thức nền và nộp bài trực tuyến.',c.id,'sub-math','Toán',
  'teacher-'||lpad((((substring(c.code from 'A([0-9]+)$')::int-1)%6)+1)::text,3,'0'),u.full_name,timestamp with time zone '2026-08-10 23:59:00+07',true,'PUBLISHED',now(),now(),0
FROM classes c JOIN users u ON u.id='teacher-'||lpad((((substring(c.code from 'A([0-9]+)$')::int-1)%6)+1)::text,3,'0') WHERE c.academic_year_id='ay-2026';
INSERT INTO assignment_submissions(id,assignment_id,student_id,student_name,content,status,submitted_at,score,feedback,graded_at,graded_by,resubmission_allowed,attempt_number,version)
SELECT 'submission-'||u.id,'assignment-current-'||u.class_id,u.id,u.full_name,'Bài làm ôn tập đầu năm',
  CASE WHEN rn%5=0 THEN 'NOT_SUBMITTED' WHEN rn%4=0 THEN 'SUBMITTED' ELSE 'GRADED' END,
  CASE WHEN rn%5=0 THEN NULL ELSE now()-interval '1 day' END,CASE WHEN rn%4<>0 AND rn%5<>0 THEN 7.0+(rn%25)/10.0 ELSE NULL END,
  CASE WHEN rn%4<>0 AND rn%5<>0 THEN 'Hoàn thành tốt yêu cầu' ELSE NULL END,
  CASE WHEN rn%4<>0 AND rn%5<>0 THEN now() ELSE NULL END,CASE WHEN rn%4<>0 AND rn%5<>0 THEN 'teacher-001' ELSE NULL END,false,1,0
FROM (SELECT u.*,row_number() over(order by u.id) rn FROM users u WHERE role='STUDENT' AND class_id LIKE 'class-current-%') u;

WITH picked AS (SELECT u.*,row_number() over(order by u.id) rn FROM users u WHERE role='STUDENT' AND class_id LIKE 'class-current-%' LIMIT 24),
parent_map AS (SELECT ps.student_id,ps.parent_id,p.full_name parent_name FROM parent_student ps JOIN users p ON p.id=ps.parent_id)
INSERT INTO leave_requests(id,student_id,student_name,class_id,class_code,status,parent_id,parent_name,homeroom_teacher_id,homeroom_teacher_name,reason,decision_note,
  start_date,end_date,created_at,updated_at,parent_confirmed_at,decided_at)
SELECT 'leave-'||p.id,p.id,p.full_name,p.class_id,p.class_name,CASE p.rn%3 WHEN 0 THEN 'APPROVED' WHEN 1 THEN 'PARENT_CONFIRMED' ELSE 'PENDING_PARENT' END,
  pm.parent_id,pm.parent_name,c.homeroom_teacher_id,c.homeroom_teacher_name,'Khám sức khỏe theo lịch hẹn',
  CASE WHEN p.rn%3=0 THEN 'Đã xác minh thông tin phụ huynh' ELSE NULL END,date '2026-08-05',date '2026-08-05',now()-interval '2 days',now(),
  CASE WHEN p.rn%3<>2 THEN now()-interval '1 day' ELSE NULL END,CASE WHEN p.rn%3=0 THEN now() ELSE NULL END
FROM picked p JOIN parent_map pm ON pm.student_id=p.id JOIN classes c ON c.id=p.class_id;

-- Tài chính: hai đợt thu, hóa đơn đủ 1.500 học sinh đang học và trạng thái thanh toán đa dạng.
INSERT INTO fee_periods(id,code,name,academic_year_id,apply_to_grades,due_date,status,created_at) VALUES
('fee-2026-hk1','THU-HK1-2026','Các khoản thu học kỳ 1','ay-2026','K10,K11,K12',date '2026-08-25','OPEN',now()),
('fee-2026-bhyt','BHYT-2026','Bảo hiểm y tế năm học 2026-2027','ay-2026','K10,K11,K12',date '2026-09-10','PUBLISHED',now());
INSERT INTO fee_period_items(id,fee_period_id,name,grade_level,amount)
SELECT 'fee-item-hk1-'||g,'fee-2026-hk1','Học phí và dịch vụ học kỳ 1',g,1800000 FROM (VALUES('K10'),('K11'),('K12')) x(g)
UNION ALL SELECT 'fee-item-bhyt-'||g,'fee-2026-bhyt','Bảo hiểm y tế',g,680000 FROM (VALUES('K10'),('K11'),('K12')) x(g);
WITH students AS (SELECT u.*,row_number() over(order by u.id) rn,ps.parent_id,c.grade_level FROM users u JOIN parent_student ps ON ps.student_id=u.id JOIN classes c ON c.id=u.class_id WHERE u.role='STUDENT' AND u.class_id LIKE 'class-current-%'),
fees AS (SELECT * FROM (VALUES('fee-2026-hk1','HK1',1800000::bigint,date '2026-08-25'),('fee-2026-bhyt','BHYT',680000::bigint,date '2026-09-10')) f(fee_id,code,amount,due_date))
INSERT INTO invoices(id,code,fee_period_id,student_id,student_name,parent_id,total_amount,paid_amount,status,due_date,issued_at,version,class_id,class_code,grade_level)
SELECT 'invoice-'||f.code||'-'||s.id,'HD-'||f.code||'-'||lpad(s.rn::text,5,'0'),f.fee_id,s.id,s.full_name,s.parent_id,f.amount,
  CASE s.rn%4 WHEN 0 THEN f.amount WHEN 1 THEN f.amount/2 ELSE 0 END,
  CASE s.rn%4 WHEN 0 THEN 'PAID' WHEN 1 THEN 'PARTIAL' WHEN 2 THEN 'ISSUED' ELSE 'OVERDUE' END,f.due_date,now()-interval '5 days',0,s.class_id,s.class_name,s.grade_level
FROM students s CROSS JOIN fees f;
INSERT INTO invoice_items(id,invoice_id,name,amount)
SELECT 'invoice-item-'||i.id,i.id,CASE i.fee_period_id WHEN 'fee-2026-hk1' THEN 'Học phí và dịch vụ học kỳ 1' ELSE 'Bảo hiểm y tế' END,i.total_amount FROM invoices i;
INSERT INTO payments(id,invoice_id,amount,method,status,txn_ref,receipt_code,payer_name,note,recorded_by,created_at,paid_at)
SELECT 'payment-'||i.id,i.id,i.paid_amount,'VIETQR','SUCCESS','VQR'||replace(i.code,'-',''),'PT-'||replace(i.code,'HD-',''),p.full_name,
  'Thanh toán đối soát tự động qua VietQR','accountant-1',now()-interval '1 day',now()-interval '1 day'
FROM invoices i JOIN users p ON p.id=i.parent_id WHERE i.paid_amount>0;

-- Một kỳ thi giữa kỳ với ba môn chính, đầy đủ lớp, phòng, SBD, giám thị và giáo viên chấm.
INSERT INTO exam_periods(id,code,name,academic_year_id,semester_id,grade_level,start_date,end_date,status,score_entry_locked,confirmed_at,confirmed_by,
  created_at,created_by,updated_at,schedule_published,schedule_revision,schedule_published_at,schedule_published_by,auto_generated) VALUES
('exam-mid-2026','GK1-2026','Kiểm tra giữa học kỳ 1','ay-2026','sem-2026-1',NULL,date '2026-10-19',date '2026-10-20','CONFIRMED',true,now(),'academic-1',now(),'academic-1',now(),true,1,now(),'academic-1',false);
INSERT INTO exam_schedules(id,exam_period_id,subject_id,subject_name,exam_date,start_time,duration_minutes,notes) VALUES
('exam-schedule-math','exam-mid-2026','sub-math','Toán',date '2026-10-19','07:30',90,'Thi tập trung toàn trường'),
('exam-schedule-lit','exam-mid-2026','sub-lit','Ngữ văn',date '2026-10-19','13:30',90,'Thi tập trung toàn trường'),
('exam-schedule-eng','exam-mid-2026','sub-eng','Tiếng Anh',date '2026-10-20','07:30',60,'Thi tập trung toàn trường');
INSERT INTO exam_schedule_classes(schedule_id,class_id)
SELECT s.id,c.id FROM exam_schedules s CROSS JOIN classes c WHERE c.academic_year_id='ay-2026';
WITH schedules AS (SELECT id FROM exam_schedules),room_no AS (
  SELECT row_number() over(order by CASE WHEN room_type='GENERAL' THEN 0 ELSE 1 END,code)::int n,code room_code
  FROM rooms WHERE status='ACTIVE' ORDER BY CASE WHEN room_type='GENERAL' THEN 0 ELSE 1 END,code LIMIT 34
)
INSERT INTO exam_rooms(id,schedule_id,room_code,capacity,proctor_one_id,proctor_one_name,proctor_two_id,proctor_two_name)
SELECT 'exam-room-'||s.id||'-'||lpad(r.n::text,2,'0'),s.id,
  r.room_code,45,
  'teacher-'||lpad((((r.n-1)%72)+1)::text,3,'0'),p1.full_name,
  'teacher-'||lpad((((r.n+33)%72)+1)::text,3,'0'),p2.full_name
FROM schedules s CROSS JOIN room_no r
JOIN users p1 ON p1.id='teacher-'||lpad((((r.n-1)%72)+1)::text,3,'0')
JOIN users p2 ON p2.id='teacher-'||lpad((((r.n+33)%72)+1)::text,3,'0');
WITH students AS (SELECT u.*,row_number() over(order by u.id) rn FROM users u WHERE role='STUDENT' AND class_id LIKE 'class-current-%'),schedules AS (SELECT id FROM exam_schedules)
INSERT INTO exam_candidates(id,exam_period_id,schedule_id,exam_room_id,student_id,student_name,student_code,class_id,class_code,candidate_no,seat_no,desk_no,seat_position)
SELECT 'candidate-'||s.id||'-'||st.id,'exam-mid-2026',s.id,'exam-room-'||s.id||'-'||lpad(ceil(st.rn/45.0)::int::text,2,'0'),
  st.id,st.full_name,st.student_code,st.class_id,st.class_name,lpad(st.rn::text,6,'0'),((st.rn-1)%45)+1,((st.rn-1)%45)+1,1
FROM students st CROSS JOIN schedules s;
WITH curriculum(subject_id,subject_name,subject_no) AS (VALUES('sub-math','Toán',1),('sub-lit','Ngữ văn',2),('sub-eng','Tiếng Anh',3))
INSERT INTO exam_grading_assignments(id,exam_period_id,schedule_id,class_id,class_code,subject_id,subject_name,teacher_id,teacher_name,assigned_at,assigned_by)
SELECT 'grader-'||e.id||'-'||c.id,'exam-mid-2026',e.id,c.id,c.code,x.subject_id,x.subject_name,
  'teacher-'||lpad((((x.subject_no-1)*6)+((substring(c.code from 'A([0-9]+)$')::int-1)%6)+1)::text,3,'0'),u.full_name,now(),'academic-1'
FROM curriculum x JOIN exam_schedules e ON e.subject_id=x.subject_id CROSS JOIN classes c
JOIN users u ON u.id='teacher-'||lpad((((x.subject_no-1)*6)+((substring(c.code from 'A([0-9]+)$')::int-1)%6)+1)::text,3,'0') WHERE c.academic_year_id='ay-2026';

-- Thông báo, tin nhắn và nhật ký hệ thống.
INSERT INTO announcements(id,title,body,audience,category,priority,status,recipient_count,created_by,created_at,sent_at) VALUES
('announcement-opening','Chào mừng năm học 2026-2027','Nhà trường chào mừng học sinh, phụ huynh và giáo viên bước vào năm học mới.','ALL','EVENT','IMPORTANT','SENT',4073,'admin-1',now()-interval '2 days',now()-interval '2 days'),
('announcement-holiday','Thông báo lịch nghỉ Quốc khánh','Toàn trường nghỉ theo lịch và không thực hiện điểm danh trong ngày nghỉ.','ALL','HOLIDAY','IMPORTANT','SENT',4073,'admin-1',now()-interval '1 day',now()-interval '1 day');
INSERT INTO notifications(id,recipient_id,type,title,body,ref_type,ref_id,priority,read,created_at)
SELECT 'notification-opening-'||u.id,u.id,'ANNOUNCEMENT','Chào mừng năm học 2026-2027','Thông báo năm học mới đã được phát hành.','ANNOUNCEMENT','announcement-opening','IMPORTANT',row_number() over(order by u.id)%3=0,now()-interval '2 days'
FROM users u WHERE role IN ('TEACHER','STUDENT','PARENT');
INSERT INTO chat_messages(id,sender_id,sender_name,recipient_id,recipient_name,body,read_flag,read_at,created_at)
SELECT 'message-'||lpad(n::text,3,'0'),CASE WHEN n%2=0 THEN 'teacher-019' ELSE 'student-2026-'||lpad(n::text,3,'0') END,
  CASE WHEN n%2=0 THEN (SELECT full_name FROM users WHERE id='teacher-019') ELSE (SELECT full_name FROM users WHERE id='student-2026-'||lpad(n::text,3,'0')) END,
  CASE WHEN n%2=0 THEN 'student-2026-'||lpad(n::text,3,'0') ELSE 'teacher-019' END,
  CASE WHEN n%2=0 THEN (SELECT full_name FROM users WHERE id='student-2026-'||lpad(n::text,3,'0')) ELSE (SELECT full_name FROM users WHERE id='teacher-019') END,
  CASE WHEN n%2=0 THEN 'Em lưu ý hoàn thành bài tập đúng hạn nhé.' ELSE 'Em đã nhận được thông báo của thầy/cô.' END,n%3=0,
  CASE WHEN n%3=0 THEN now() ELSE NULL END,now()-make_interval(hours=>n)
FROM generate_series(1,30) n;
INSERT INTO audit_logs(id,actor_id,actor_name,role,action,module,entity_type,entity_id,detail,created_at) VALUES
('audit-realistic-seed','admin-1','Bùi Đức Trung','ADMIN','RESET_AND_SEED','system','database','school-management',
 'Khởi tạo mới toàn bộ dữ liệu trường THPT thực tế cho năm học 2025-2026 và 2026-2027',now());

COMMIT;
