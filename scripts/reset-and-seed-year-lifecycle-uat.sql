\set ON_ERROR_STOP on
SET client_encoding TO 'UTF8';

BEGIN;

-- Giữ lại ba tài khoản quản lý; làm sạch toàn bộ dữ liệu nghiệp vụ và tài khoản vai trò còn lại.
DO $$
DECLARE
    targets text;
BEGIN
    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ' ORDER BY tablename)
    INTO targets
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename NOT IN ('flyway_schema_history', 'users');
    EXECUTE 'TRUNCATE TABLE ' || targets || ' CASCADE';
END $$;

DELETE FROM users WHERE role NOT IN ('ADMIN', 'ACADEMIC_STAFF', 'ACCOUNTANT');
UPDATE users
SET class_id=NULL, class_name=NULL, cohort_id=NULL, student_status=NULL,
    graduated_at=NULL, graduation_academic_year_id=NULL, graduation_class_id=NULL
WHERE role IN ('ADMIN', 'ACADEMIC_STAFF', 'ACCOUNTANT');

UPDATE users
SET full_name = CASE username
    WHEN 'admin' THEN 'Bùi Đức Trung'
    WHEN 'giaovu' THEN 'Nguyễn Thị Giáo Vụ'
    WHEN 'ketoan' THEN 'Trần Thị Kế Toán'
    ELSE full_name
END
WHERE username IN ('admin', 'giaovu', 'ketoan');

-- Danh mục nền: 12 môn, bốn đầu điểm và phòng học cho hai niên khóa.
INSERT INTO subjects(id,code,name,coefficient,status) VALUES
('sub-math','MATH','Toán',1,'ACTIVE'),
('sub-lit','LIT','Ngữ văn',1,'ACTIVE'),
('sub-eng','ENG','Tiếng Anh',1,'ACTIVE'),
('sub-phys','PHYS','Vật lý',1,'ACTIVE'),
('sub-chem','CHEM','Hóa học',1,'ACTIVE'),
('sub-bio','BIO','Sinh học',1,'ACTIVE'),
('sub-hist','HIST','Lịch sử',1,'ACTIVE'),
('sub-geo','GEO','Địa lý',1,'ACTIVE'),
('sub-it','IT','Tin học',1,'ACTIVE'),
('sub-tech','TECH','Công nghệ',1,'ACTIVE'),
('sub-pe','PE','Giáo dục thể chất',1,'ACTIVE'),
('sub-civic','CIVIC','Giáo dục KT&PL',1,'ACTIVE');

INSERT INTO exam_categories(id,code,name,weight,required_count) VALUES
('cat-oral','ORAL','Kiểm tra miệng',1,1),
('cat-15m','15M','Kiểm tra 15 phút',1,1),
('cat-mid','MID','Giữa học kỳ',2,1),
('cat-final','FINAL','Cuối học kỳ',3,1);

INSERT INTO rooms(id,code,name,capacity,supports_morning,supports_afternoon,status) VALUES
('room-a101','A101','Phòng A101',45,true,false,'ACTIVE'),
('room-a102','A102','Phòng A102',45,false,true,'ACTIVE'),
('room-a103','A103','Phòng A103',45,true,false,'ACTIVE'),
('room-a201','A201','Phòng A201',45,true,false,'ACTIVE'),
('room-a202','A202','Phòng A202',45,false,true,'ACTIVE'),
('room-a203','A203','Phòng A203',45,true,false,'ACTIVE'),
('room-a204','A204','Phòng A204',45,false,true,'ACTIVE'),
('room-lab','LAB01','Phòng thực hành',40,true,true,'ACTIVE');

-- Một năm cũ đang chờ tổng kết và một năm mới đang chờ khởi động.
INSERT INTO academic_years
    (id,code,name,start_date,end_date,status,orientation_start_date,opening_date,instruction_weeks,auto_generated)
VALUES
('ay-old-2025','2025-2026','Năm học 2025-2026',date '2025-08-18',date '2026-05-31','ACTIVE',date '2025-08-11',date '2025-09-05',35,false),
('ay-new-2026','2026-2027','Năm học 2026-2027',date '2026-08-15',date '2027-05-31','PLANNED',date '2026-08-10',date '2026-09-05',35,false);

INSERT INTO semesters
    (id,academic_year_id,code,name,sequence,start_date,end_date,status,instruction_weeks,auto_generated)
VALUES
('sem-old-hk1','ay-old-2025','HK1','Học kỳ 1',1,date '2025-08-18',date '2026-01-11','CLOSED',18,false),
('sem-old-hk2','ay-old-2025','HK2','Học kỳ 2',2,date '2026-01-12',date '2026-05-31','CLOSED',17,false),
('sem-new-hk1','ay-new-2026','HK1','Học kỳ 1',1,date '2026-08-15',date '2027-01-15','PLANNED',18,false),
('sem-new-hk2','ay-new-2026','HK2','Học kỳ 2',2,date '2027-01-18',date '2027-05-31','PLANNED',17,false);

INSERT INTO cohorts(id,code,name,entry_year,graduation_year,duration_years,status,entry_academic_year_id,created_at,created_by) VALUES
('cohort-2023','2023-2026','Niên khóa 2023-2026',2023,2026,3,'ACTIVE','ay-old-2025',now(),'admin-1'),
('cohort-2024','2024-2027','Niên khóa 2024-2027',2024,2027,3,'ACTIVE','ay-old-2025',now(),'admin-1'),
('cohort-2025','2025-2028','Niên khóa 2025-2028',2025,2028,3,'ACTIVE','ay-old-2025',now(),'admin-1'),
('cohort-2026','2026-2029','Niên khóa 2026-2029',2026,2029,3,'ACTIVE','ay-new-2026',now(),'admin-1');

-- Mười hai giáo viên, mỗi người phụ trách đúng một môn.
WITH teacher_data(id,username,full_name,code,subject_id,subject_name,email,hash) AS (VALUES
('teacher-math','gv.nguyenminh','Nguyễn Đức Minh','GV001','sub-math','Toán','gv.nguyenminh@school.local','$2a$10$Vav5RTJMnCL8hNOz6bfDcOYH8iuaaDQ6X3uBbYu6adItFdfAL0toK'),
('teacher-lit','gv.tranthuha','Trần Thu Hà','GV002','sub-lit','Ngữ văn','gv.tranthuha@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-eng','gv.lehoanganh','Lê Hoàng Anh','GV003','sub-eng','Tiếng Anh','gv.lehoanganh@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-phys','gv.phamquocbao','Phạm Quốc Bảo','GV004','sub-phys','Vật lý','gv.phamquocbao@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-chem','gv.nguyenngoclan','Nguyễn Ngọc Lan','GV005','sub-chem','Hóa học','gv.nguyenngoclan@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-bio','gv.dominhchau','Đỗ Minh Châu','GV006','sub-bio','Sinh học','gv.dominhchau@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-hist','gv.vuthanhtung','Vũ Thanh Tùng','GV007','sub-hist','Lịch sử','gv.vuthanhtung@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-geo','gv.hoanghaiyen','Hoàng Hải Yến','GV008','sub-geo','Địa lý','gv.hoanghaiyen@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-it','gv.dangquanghuy','Đặng Quang Huy','GV009','sub-it','Tin học','gv.dangquanghuy@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-tech','gv.ngomaiphuong','Ngô Mai Phương','GV010','sub-tech','Công nghệ','gv.ngomaiphuong@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-pe','gv.phanvannam','Phan Văn Nam','GV011','sub-pe','Giáo dục thể chất','gv.phanvannam@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'),
('teacher-civic','gv.dohaian','Đỗ Hải An','GV012','sub-civic','Giáo dục KT&PL','gv.dohaian@school.local','$2a$10$8mYB4ELkvL4NAsBLCC9oz.EzVdCtf.YrB7FUwlROiaD083g1KISyW'))
INSERT INTO users
    (id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,
     token_version,teacher_code,main_subject,main_subject_id)
SELECT id,username,hash,full_name,email,'0901'||right('000000'||row_number() over()::text,6),
       'TEACHER','ACTIVE',now(),false,0,code,subject_name,subject_id
FROM teacher_data;

-- Lớp của năm cũ và lớp đích khối 11-12 trong năm mới.
INSERT INTO classes
    (id,academic_year_id,code,name,grade_level,homeroom_teacher_id,homeroom_teacher_name,
     homeroom_assigned_at,homeroom_assigned_by,student_count,capacity,study_shift,room_id,room_code,
     status,cohort_id,planned_student_count,auto_generated)
VALUES
('class-old-10a1','ay-old-2025','10A1','Lớp 10A1','K10','teacher-math','Nguyễn Đức Minh',now(),'admin-1',4,40,'MORNING','room-a101','A101','ACTIVE','cohort-2025',4,false),
('class-old-11a1','ay-old-2025','11A1','Lớp 11A1','K11','teacher-lit','Trần Thu Hà',now(),'admin-1',4,40,'AFTERNOON','room-a102','A102','ACTIVE','cohort-2024',4,false),
('class-old-12a1','ay-old-2025','12A1','Lớp 12A1','K12','teacher-eng','Lê Hoàng Anh',now(),'admin-1',4,40,'MORNING','room-a103','A103','ACTIVE','cohort-2023',4,false),
('class-new-11a1','ay-new-2026','11A1','Lớp 11A1','K11','teacher-math','Nguyễn Đức Minh',now(),'admin-1',0,40,'MORNING','room-a201','A201','ACTIVE','cohort-2025',4,false),
('class-new-12a1','ay-new-2026','12A1','Lớp 12A1','K12','teacher-lit','Trần Thu Hà',now(),'admin-1',0,40,'AFTERNOON','room-a202','A202','ACTIVE','cohort-2024',4,false);

-- Mười hai học sinh của năm cũ: bốn em mỗi khối 10, 11 và 12.
WITH old_students(id,username,full_name,student_code,class_id,class_code,cohort_id,dob,gender,hash) AS (VALUES
('old-student-01','hs.nguyenminhan','Nguyễn Minh An','HS250001','class-old-10a1','10A1','cohort-2025',date '2010-01-15','MALE','$2a$10$Y8J/yvtyHP83MNPQ.oikV.hTR3D1X2KNV5v3DwTFNFXSW3a7Gvk3.'),
('old-student-02','hs.cunam02','Trần Gia Hân','HS250002','class-old-10a1','10A1','cohort-2025',date '2010-02-20','FEMALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-03','hs.cunam03','Lê Minh Khang','HS250003','class-old-10a1','10A1','cohort-2025',date '2010-03-18','MALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-04','hs.cunam04','Phạm Bảo Ngọc','HS250004','class-old-10a1','10A1','cohort-2025',date '2010-04-11','FEMALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-05','hs.cunam05','Hoàng Đức Anh','HS240001','class-old-11a1','11A1','cohort-2024',date '2009-01-22','MALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-06','hs.cunam06','Vũ Khánh Linh','HS240002','class-old-11a1','11A1','cohort-2024',date '2009-02-13','FEMALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-07','hs.cunam07','Đỗ Quốc Huy','HS240003','class-old-11a1','11A1','cohort-2024',date '2009-03-09','MALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-08','hs.cunam08','Bùi Minh Thư','HS240004','class-old-11a1','11A1','cohort-2024',date '2009-04-17','FEMALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-09','hs.cunam09','Ngô Anh Tuấn','HS230001','class-old-12a1','12A1','cohort-2023',date '2008-01-12','MALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-10','hs.cunam10','Dương Hải Yến','HS230002','class-old-12a1','12A1','cohort-2023',date '2008-02-24','FEMALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-11','hs.cunam11','Đặng Thành Nam','HS230003','class-old-12a1','12A1','cohort-2023',date '2008-03-19','MALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'),
('old-student-12','hs.cunam12','Hồ Ngọc Mai','HS230004','class-old-12a1','12A1','cohort-2023',date '2008-04-28','FEMALE','$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq'))
INSERT INTO users
    (id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version,
     student_code,class_id,class_name,cohort_id,student_status,date_of_birth,gender,nationality,address,enrollment_date,
     guardian_name,guardian_phone)
SELECT id,username,hash,full_name,username||'@school.local','0912'||right('000000'||row_number() over()::text,6),
       'STUDENT','ACTIVE',now(),false,0,student_code,class_id,class_code,cohort_id,'ENROLLED',dob,gender,'Việt Nam',
       'Hà Nội',date '2023-08-20','Phụ huynh '||full_name,'0988'||right('000000'||row_number() over()::text,6)
FROM old_students;

INSERT INTO class_enrollments(id,student_id,class_id,academic_year_id,status,enrolled_at,cohort_id)
SELECT 'enrollment-old-'||right(id,2),id,class_id,'ay-old-2025','ACTIVE',timestamp with time zone '2025-08-18 07:00:00+07',cohort_id
FROM users WHERE id LIKE 'old-student-%';

-- Phụ huynh của học sinh năm cũ; tài khoản đầu tiên giữ đúng thông tin demo quen thuộc.
WITH parent_data(n,id,username,full_name,hash) AS (
    SELECT n,'old-parent-'||lpad(n::text,2,'0'),
           CASE WHEN n=1 THEN 'ph.nguyenvanhung' ELSE 'ph.cunam'||lpad(n::text,2,'0') END,
           CASE WHEN n=1 THEN 'Nguyễn Văn Hùng' ELSE 'Phụ huynh học sinh cũ '||lpad(n::text,2,'0') END,
           CASE WHEN n=1 THEN '$2a$10$Ksdv13aesVO46/Nk1FDliuft1Ks.xRJnKAjmJboabxPIQWWi.bS66'
                ELSE '$2a$10$sRwxarFdguIuoUYJFTiItu45AmtcKsFGQhelk0QykKlre2rBGZ17q' END
    FROM generate_series(1,12) n
)
INSERT INTO users(id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version)
SELECT id,username,hash,full_name,username||'@gmail.com','0987'||lpad(n::text,6,'0'),
       'PARENT','ACTIVE',now(),false,0 FROM parent_data;

INSERT INTO parent_student(id,parent_id,student_id,primary_contact)
SELECT 'relation-old-'||lpad(n::text,2,'0'),'old-parent-'||lpad(n::text,2,'0'),
       'old-student-'||lpad(n::text,2,'0'),true FROM generate_series(1,12) n;

-- Định mức và phân công đủ 12 môn cho cả hai học kỳ của năm cũ.
WITH curriculum(subject_id,subject_name,weekly_periods,teacher_id) AS (VALUES
('sub-math','Toán',4,'teacher-math'),('sub-lit','Ngữ văn',4,'teacher-lit'),('sub-eng','Tiếng Anh',3,'teacher-eng'),
('sub-phys','Vật lý',3,'teacher-phys'),('sub-chem','Hóa học',2,'teacher-chem'),('sub-bio','Sinh học',2,'teacher-bio'),
('sub-hist','Lịch sử',2,'teacher-hist'),('sub-geo','Địa lý',2,'teacher-geo'),('sub-it','Tin học',2,'teacher-it'),
('sub-tech','Công nghệ',2,'teacher-tech'),('sub-pe','Giáo dục thể chất',2,'teacher-pe'),('sub-civic','Giáo dục KT&PL',2,'teacher-civic')),
scope AS (
    SELECT c.id class_id,c.code class_code,s.id semester_id,s.start_date,s.end_date
    FROM classes c CROSS JOIN semesters s
    WHERE c.academic_year_id='ay-old-2025' AND s.academic_year_id='ay-old-2025'
)
INSERT INTO teaching_assignments
    (id,class_id,class_code,semester_id,subject_id,subject_name,teacher_id,teacher_name,weekly_periods,
     assigned_at,assigned_by,updated_at,effective_from,effective_to,status,version)
SELECT 'assignment-'||scope.semester_id||'-'||scope.class_code||'-'||curriculum.subject_id,
       scope.class_id,scope.class_code,scope.semester_id,curriculum.subject_id,curriculum.subject_name,
       curriculum.teacher_id,u.full_name,curriculum.weekly_periods,now(),'admin-1',now(),scope.start_date,scope.end_date,'ACTIVE',0
FROM scope CROSS JOIN curriculum JOIN users u ON u.id=curriculum.teacher_id;

-- Đủ bốn đầu điểm của 12 môn trong cả hai học kỳ; HS08 là trường hợp lưu ban có chủ đích.
WITH old_students AS (
    SELECT id,row_number() over(order by id) rn FROM users WHERE id LIKE 'old-student-%'
), subject_list AS (
    SELECT id,name,row_number() over(order by id) rn FROM subjects
), semester_list AS (
    SELECT id,sequence FROM semesters WHERE academic_year_id='ay-old-2025'
), categories(code,name,weight_order) AS (VALUES
    ('ORAL','Kiểm tra miệng',1),('15M','Kiểm tra 15 phút',2),('MID','Giữa học kỳ',3),('FINAL','Cuối học kỳ',4)
)
INSERT INTO grades
    (id,student_id,subject_id,subject_name,semester_id,category,category_name,assessment_index,score,note,
     recorded_at,created_at,created_by,updated_at,updated_by,version)
SELECT 'grade-'||st.id||'-'||sem.id||'-'||sub.id||'-'||cat.code,
       st.id,sub.id,sub.name,sem.id,cat.code,cat.name,1,
       CASE WHEN st.id='old-student-08' THEN 4.2
            ELSE round((6.8+((st.rn+sub.rn+sem.sequence+cat.weight_order)%24)/10.0)::numeric,1) END,
       'Dữ liệu nghiệm thu đủ đầu điểm',now(),now(),
       CASE sub.id WHEN 'sub-math' THEN 'teacher-math' WHEN 'sub-lit' THEN 'teacher-lit'
            WHEN 'sub-eng' THEN 'teacher-eng' WHEN 'sub-phys' THEN 'teacher-phys'
            WHEN 'sub-chem' THEN 'teacher-chem' WHEN 'sub-bio' THEN 'teacher-bio'
            WHEN 'sub-hist' THEN 'teacher-hist' WHEN 'sub-geo' THEN 'teacher-geo'
            WHEN 'sub-it' THEN 'teacher-it' WHEN 'sub-tech' THEN 'teacher-tech'
            WHEN 'sub-pe' THEN 'teacher-pe' ELSE 'teacher-civic' END,
       now(),
       CASE sub.id WHEN 'sub-math' THEN 'teacher-math' WHEN 'sub-lit' THEN 'teacher-lit'
            WHEN 'sub-eng' THEN 'teacher-eng' WHEN 'sub-phys' THEN 'teacher-phys'
            WHEN 'sub-chem' THEN 'teacher-chem' WHEN 'sub-bio' THEN 'teacher-bio'
            WHEN 'sub-hist' THEN 'teacher-hist' WHEN 'sub-geo' THEN 'teacher-geo'
            WHEN 'sub-it' THEN 'teacher-it' WHEN 'sub-tech' THEN 'teacher-tech'
            WHEN 'sub-pe' THEN 'teacher-pe' ELSE 'teacher-civic' END,0
FROM old_students st CROSS JOIN subject_list sub CROSS JOIN semester_list sem CROSS JOIN categories cat;

INSERT INTO student_yearly_summaries
    (id,academic_year_id,student_id,student_name,class_id,conduct_grade,conduct_note,promotion_status,
     missing_requirements,updated_at,conduct_updated_by,version)
SELECT 'summary-'||u.id,'ay-old-2025',u.id,u.full_name,u.class_id,
       CASE WHEN u.id='old-student-08' THEN 'WEAK' ELSE 'GOOD' END,
       CASE WHEN u.id='old-student-08' THEN 'Cần rèn luyện thêm để kiểm thử luồng lưu ban'
            ELSE 'Đã hoàn thành đánh giá hạnh kiểm cuối năm' END,
       'INCOMPLETE',NULL,now(),c.homeroom_teacher_id,0
FROM users u JOIN classes c ON c.id=u.class_id WHERE u.id LIKE 'old-student-%';

-- Thời khóa biểu lịch sử của năm cũ: 30 tiết/lớp/học kỳ và hai phiên bản đã phát hành.
WITH curriculum(ord,subject_id,subject_name,teacher_id,weekly_periods) AS (VALUES
(1,'sub-math','Toán','teacher-math',4),(2,'sub-lit','Ngữ văn','teacher-lit',4),(3,'sub-eng','Tiếng Anh','teacher-eng',3),
(4,'sub-phys','Vật lý','teacher-phys',3),(5,'sub-chem','Hóa học','teacher-chem',2),(6,'sub-bio','Sinh học','teacher-bio',2),
(7,'sub-hist','Lịch sử','teacher-hist',2),(8,'sub-geo','Địa lý','teacher-geo',2),(9,'sub-it','Tin học','teacher-it',2),
(10,'sub-tech','Công nghệ','teacher-tech',2),(11,'sub-pe','Giáo dục thể chất','teacher-pe',2),(12,'sub-civic','Giáo dục KT&PL','teacher-civic',2)),
expanded AS (
    SELECT c.*,g.n,row_number() over(order by c.ord,g.n)-1 slot_no
    FROM curriculum c CROSS JOIN LATERAL generate_series(1,c.weekly_periods) g(n)
), class_scope AS (
    SELECT c.*,CASE c.id WHEN 'class-old-10a1' THEN 0 WHEN 'class-old-11a1' THEN 5 ELSE 10 END offset_no
    FROM classes c WHERE c.academic_year_id='ay-old-2025'
), semester_scope AS (
    SELECT * FROM semesters WHERE academic_year_id='ay-old-2025'
)
INSERT INTO timetable_slots
    (id,class_id,subject_id,subject_name,teacher_id,teacher_name,room_code,day_of_week,period_no,
     start_time,end_time,semester_id,published_plan_id,locked)
SELECT 'slot-'||sem.id||'-'||cls.code||'-'||(x.slot_no+1),cls.id,x.subject_id,x.subject_name,x.teacher_id,u.full_name,
       cls.room_code,(ARRAY['MON','TUE','WED','THU','FRI','SAT'])[(((x.slot_no+cls.offset_no)/5)::int%6)+1],
       ((x.slot_no+cls.offset_no)%5)+1,
       CASE cls.study_shift WHEN 'AFTERNOON' THEN (ARRAY['13:00','13:50','14:50','15:40','16:35'])[((x.slot_no+cls.offset_no)%5)+1]
            ELSE (ARRAY['07:00','07:50','08:50','09:40','10:35'])[((x.slot_no+cls.offset_no)%5)+1] END,
       CASE cls.study_shift WHEN 'AFTERNOON' THEN (ARRAY['13:45','14:35','15:35','16:25','17:20'])[((x.slot_no+cls.offset_no)%5)+1]
            ELSE (ARRAY['07:45','08:35','09:35','10:25','11:20'])[((x.slot_no+cls.offset_no)%5)+1] END,
       sem.id,'plan-'||sem.id,false
FROM semester_scope sem CROSS JOIN class_scope cls CROSS JOIN expanded x JOIN users u ON u.id=x.teacher_id;

INSERT INTO timetable_plans
    (id,semester_id,name,status,version_no,option_no,quality_score,progress_percent,total_assignments,
     total_periods,scheduled_periods,unscheduled_periods,conflict_summary,configuration_json,created_by,
     created_at,updated_at,published_by,published_at)
SELECT 'plan-'||id,id,'Lịch chính thức '||name,'PUBLISHED',1,1,100,100,36,90,90,0,NULL,
       '{"source":"year-lifecycle-uat"}','admin-1',now(),now(),'admin-1',now()
FROM semesters WHERE academic_year_id='ay-old-2025';

INSERT INTO timetable_plan_slots
    (id,plan_id,class_id,class_code,study_shift,subject_id,subject_name,teacher_id,teacher_name,room_code,
     day_of_week,period_no,start_time,end_time,locked,created_at)
SELECT 'snapshot-'||slot.id,slot.published_plan_id,slot.class_id,c.code,c.study_shift,slot.subject_id,
       slot.subject_name,slot.teacher_id,slot.teacher_name,slot.room_code,slot.day_of_week,slot.period_no,
       slot.start_time,slot.end_time,slot.locked,now()
FROM timetable_slots slot JOIN classes c ON c.id=slot.class_id WHERE c.academic_year_id='ay-old-2025';

-- 36 hồ sơ học sinh đầu vào chưa xếp lớp để kiểm thử tạo/xếp lớp tự động.
WITH intake AS (
    SELECT n,'intake-student-'||lpad(n::text,2,'0') id,'hs.dauvao'||lpad(n::text,2,'0') username,
           (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Vũ'])[((n-1)%6)+1]||' Học sinh đầu vào '||lpad(n::text,2,'0') full_name
    FROM generate_series(1,36) n
)
INSERT INTO users
    (id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version,
     student_code,class_id,class_name,cohort_id,student_status,date_of_birth,gender,nationality,address,enrollment_date,
     guardian_name,guardian_phone)
SELECT id,username,'$2a$10$64Q6Pj7sqZNLnIkvWoymPO9NdBVsDml/F7R2Qiu.hbZSKi0kd2Skq',full_name,
       username||'@school.local','0926'||lpad(n::text,6,'0'),'STUDENT','ACTIVE',now(),false,0,
       'TS26'||lpad(n::text,4,'0'),NULL,NULL,'cohort-2026','ENROLLED',date '2011-01-01'+n,
       CASE WHEN n%2=0 THEN 'MALE' ELSE 'FEMALE' END,'Việt Nam','Hà Nội',date '2026-07-20',
       'Phụ huynh đầu vào '||lpad(ceil(n/2.0)::int::text,2,'0'),'0976'||lpad(ceil(n/2.0)::int::text,6,'0')
FROM intake;

INSERT INTO users
    (id,username,password_hash,full_name,email,phone,role,status,created_at,password_change_required,token_version)
SELECT 'intake-parent-'||lpad(n::text,2,'0'),'ph.dauvao'||lpad(n::text,2,'0'),
       '$2a$10$sRwxarFdguIuoUYJFTiItu45AmtcKsFGQhelk0QykKlre2rBGZ17q',
       'Phụ huynh đầu vào '||lpad(n::text,2,'0'),'ph.dauvao'||lpad(n::text,2,'0')||'@gmail.com',
       '0976'||lpad(n::text,6,'0'),'PARENT','ACTIVE',now(),false,0
FROM generate_series(1,18) n;

INSERT INTO parent_student(id,parent_id,student_id,primary_contact)
SELECT 'relation-intake-'||lpad(n::text,2,'0'),
       'intake-parent-'||lpad(ceil(n/2.0)::int::text,2,'0'),
       'intake-student-'||lpad(n::text,2,'0'),true
FROM generate_series(1,36) n;

-- Định mức đủ 12 môn cho cả ba khối của năm mới; chưa tạo phân công để kiểm thử nút tự động.
WITH curriculum(subject_id,subject_name,weekly_periods) AS (VALUES
('sub-math','Toán',4),('sub-lit','Ngữ văn',4),('sub-eng','Tiếng Anh',3),('sub-phys','Vật lý',3),
('sub-chem','Hóa học',2),('sub-bio','Sinh học',2),('sub-hist','Lịch sử',2),('sub-geo','Địa lý',2),
('sub-it','Tin học',2),('sub-tech','Công nghệ',2),('sub-pe','Giáo dục thể chất',2),('sub-civic','Giáo dục KT&PL',2)),
scope AS (
    SELECT s.id semester_id,g.grade_level FROM semesters s
    CROSS JOIN (VALUES('K10'),('K11'),('K12')) g(grade_level)
    WHERE s.academic_year_id='ay-new-2026'
)
INSERT INTO curriculum_requirements(id,semester_id,grade_level,subject_id,subject_name,weekly_periods,created_at,updated_at)
SELECT 'requirement-'||scope.semester_id||'-'||scope.grade_level||'-'||c.subject_id,
       scope.semester_id,scope.grade_level,c.subject_id,c.subject_name,c.weekly_periods,now(),now()
FROM scope CROSS JOIN curriculum c;

-- HK1 đã duyệt để dùng ngay; HK2 ở trạng thái đã gửi để kiểm thử quy trình duyệt.
WITH teacher_capacity(teacher_id,max_periods,preferred_grades,unavailable) AS (VALUES
('teacher-math',18,'K10,K11,K12','MON:1'),('teacher-lit',18,'K10,K11,K12','TUE:1'),
('teacher-eng',16,'K10,K11,K12','WED:5'),('teacher-phys',16,'K10,K11,K12','THU:5'),
('teacher-chem',12,'K10,K11,K12','FRI:1'),('teacher-bio',12,'K10,K11,K12','SAT:5'),
('teacher-hist',12,'K10,K11,K12','MON:5'),('teacher-geo',12,'K10,K11,K12','TUE:5'),
('teacher-it',12,'K10,K11,K12','WED:1'),('teacher-tech',12,'K10,K11,K12','THU:1'),
('teacher-pe',12,'K10,K11,K12','FRI:5'),('teacher-civic',12,'K10,K11,K12','SAT:1')),
scope AS (
    SELECT s.id semester_id,s.sequence,t.* FROM semesters s CROSS JOIN teacher_capacity t
    WHERE s.academic_year_id='ay-new-2026'
)
INSERT INTO teacher_load_registrations
    (id,teacher_id,teacher_name,semester_id,max_weekly_periods,unavailable_slots,preferred_grade_levels,
     note,review_note,status,submitted_at,reviewed_at,reviewed_by,created_at,updated_at)
SELECT 'load-'||semester_id||'-'||teacher_id,teacher_id,u.full_name,semester_id,max_periods,unavailable,
       preferred_grades,'Đăng ký tải dạy cho năm học mới',
       CASE WHEN sequence=1 THEN 'Đã duyệt để chạy phân công tự động' ELSE NULL END,
       CASE WHEN sequence=1 THEN 'APPROVED' ELSE 'SUBMITTED' END,now()-interval '7 days',
       CASE WHEN sequence=1 THEN now()-interval '6 days' ELSE NULL END,
       CASE WHEN sequence=1 THEN 'admin-1' ELSE NULL END,now(),now()
FROM scope JOIN users u ON u.id=teacher_id;

INSERT INTO announcements
    (id,title,body,audience,category,priority,status,recipient_count,created_by,created_at,sent_at)
VALUES
('announcement-uat','Bộ dữ liệu nghiệm thu năm học',
 'Năm học 2025-2026 sẵn sàng tổng kết; năm học 2026-2027 sẵn sàng xếp học sinh đầu vào và phân công tự động.',
 'ALL','SYSTEM','IMPORTANT','SENT',81,'admin-1',now(),now());

INSERT INTO audit_logs(id,actor_id,actor_name,role,action,module,entity_type,entity_id,detail,created_at) VALUES
('audit-seed-lifecycle','admin-1','Quản trị hệ thống','ADMIN','RESET_UAT_DATA','academic','academic_year','ay-old-2025',
 'Khởi tạo bộ dữ liệu nghiệm thu tổng kết, cựu học sinh và bắt đầu năm học mới',now());

COMMIT;
