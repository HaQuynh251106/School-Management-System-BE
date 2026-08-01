\set ON_ERROR_STOP on
SET client_encoding TO 'UTF8';

BEGIN;

-- Tổng kết phải luôn được sinh lại từ điểm nguồn, không giữ số liệu tổng hợp giả.
DELETE FROM student_yearly_summaries WHERE academic_year_id='ay-2026';
DELETE FROM grades WHERE semester_id IN ('sm-2026-1','sm-2026-2');

-- Bảo đảm cả hai học kỳ đều có chương trình và phân công đủ 12 môn.
INSERT INTO curriculum_requirements
    (id,semester_id,grade_level,subject_id,subject_name,weekly_periods,created_at,updated_at)
SELECT 'cur-hk2-'||grade_level||'-'||subject_id,'sm-2026-2',grade_level,subject_id,subject_name,
       weekly_periods,now(),now()
FROM curriculum_requirements
WHERE semester_id='sm-2026-1'
ON CONFLICT (id) DO UPDATE SET
    subject_name=excluded.subject_name,
    weekly_periods=excluded.weekly_periods,
    updated_at=excluded.updated_at;

INSERT INTO teaching_assignments
    (id,class_id,class_code,semester_id,subject_id,subject_name,teacher_id,teacher_name,
     weekly_periods,assigned_at,assigned_by,updated_at,effective_from,effective_to,status,version)
SELECT 'ta-hk2-'||class_code||'-'||subject_id,class_id,class_code,'sm-2026-2',subject_id,subject_name,
       teacher_id,teacher_name,weekly_periods,now(),'u-academic-staff-1',now(),
       date '2027-01-18',date '2027-05-31','ACTIVE',0
FROM teaching_assignments
WHERE semester_id='sm-2026-1'
ON CONFLICT (id) DO UPDATE SET
    subject_name=excluded.subject_name,
    teacher_id=excluded.teacher_id,
    teacher_name=excluded.teacher_name,
    weekly_periods=excluded.weekly_periods,
    updated_at=excluded.updated_at;

-- Xóa phân công mồ côi của dữ liệu seed cũ và chuẩn hóa số tiết theo chương trình.
DELETE FROM teaching_assignments ta
WHERE NOT EXISTS (SELECT 1 FROM classes c WHERE c.id=ta.class_id);

UPDATE teaching_assignments ta
SET weekly_periods=cr.weekly_periods,
    subject_name=cr.subject_name,
    updated_at=now()
FROM classes c
JOIN curriculum_requirements cr ON cr.grade_level=c.grade_level
WHERE c.id=ta.class_id
  AND cr.semester_id=ta.semester_id
  AND cr.subject_id=ta.subject_id
  AND ta.semester_id IN ('sm-2026-1','sm-2026-2');

INSERT INTO teacher_load_registrations
    (id,teacher_id,teacher_name,semester_id,max_weekly_periods,preferred_grade_levels,status,
     submitted_at,reviewed_at,reviewed_by,created_at,updated_at,note,review_note)
SELECT 'load-hk2-'||teacher_id,teacher_id,teacher_name,'sm-2026-2',max_weekly_periods,
       preferred_grade_levels,'APPROVED',now(),now(),'u-academic-staff-1',now(),now(),
       'Đăng ký tải dạy học kỳ 2','Giáo vụ đã duyệt'
FROM teacher_load_registrations
WHERE semester_id='sm-2026-1'
ON CONFLICT (id) DO UPDATE SET
    teacher_name=excluded.teacher_name,
    max_weekly_periods=excluded.max_weekly_periods,
    status='APPROVED',
    updated_at=excluded.updated_at;

-- Sinh đúng số đầu điểm đang được cấu hình cho mọi học sinh, 12 môn, 2 học kỳ.
-- Điểm được tạo xác định để có thể chạy lại và đối chiếu kết quả tổng kết.
WITH enrolled_students AS (
    SELECT u.id, u.class_id, row_number() OVER (ORDER BY u.id) AS student_no
    FROM users u
    WHERE u.role='STUDENT'
      AND u.class_id IN ('c-10a1','c-10a2','c-11a1','c-11a2','c-12a1','c-12a2')
), semester_subjects AS (
    SELECT DISTINCT ta.semester_id,ta.class_id,ta.subject_id,ta.subject_name,ta.teacher_id
    FROM teaching_assignments ta
    WHERE ta.semester_id IN ('sm-2026-1','sm-2026-2') AND ta.status='ACTIVE'
), required_assessments AS (
    SELECT ec.code AS category,ec.name AS category_name,
           generate_series(1,GREATEST(ec.required_count,1)) AS assessment_index
    FROM exam_categories ec
)
INSERT INTO grades
    (id,student_id,subject_id,subject_name,semester_id,category,category_name,assessment_index,
     score,note,recorded_at,created_at,created_by,updated_at,updated_by,version)
SELECT 'uat-grade-'||ss.semester_id||'-'||st.id||'-'||ss.subject_id||'-'||ra.category||'-'||ra.assessment_index,
       st.id,ss.subject_id,ss.subject_name,ss.semester_id,ra.category,ra.category_name,ra.assessment_index,
       round((
           6.0
           + ((st.student_no + length(ss.subject_id) + ra.assessment_index
               + CASE ss.semester_id WHEN 'sm-2026-2' THEN 3 ELSE 0 END) % 31) / 10.0
       )::numeric,1),
       'Dữ liệu điểm nguồn phục vụ nghiệm thu',now(),now(),ss.teacher_id,now(),ss.teacher_id,0
FROM enrolled_students st
JOIN semester_subjects ss ON ss.class_id=st.class_id
CROSS JOIN required_assessments ra;

COMMIT;
