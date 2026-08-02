\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';

BEGIN;

-- Keep the stable UAT accounts, but make the remaining operational dataset
-- distinguishable. The generated combinations are deterministic and unique for
-- the 2,000 seeded students/parents and 72 subject teachers.
CREATE TEMP TABLE repaired_student_names ON COMMIT DROP AS
WITH source AS (
  SELECT id,
         split_part(id, '-', 2)::int cohort_year,
         split_part(id, '-', 3)::int student_no
  FROM users
  WHERE role = 'STUDENT' AND id ~ '^student-[0-9]{4}-[0-9]{3}$'
), numbered AS (
  SELECT *, (cohort_year - 2023) * 500 + student_no AS person_no
  FROM source
)
SELECT id,
       CASE WHEN id = 'student-2026-001' THEN 'Nguyễn Minh An' ELSE
         (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Vũ','Đặng','Đỗ','Bùi','Ngô','Dương','Đinh'])[((person_no - 1) % 12) + 1] || ' ' ||
         (ARRAY['Minh','Gia','Khánh','Bảo','Thanh','Hoài','Đức','Ngọc'])[(((person_no - 1) / 12) % 8) + 1] || ' ' ||
         (ARRAY['Ân','Anh','Bình','Châu','Dũng','Giang','Hà','Hải','Hân','Hùng','Hương','Khang','Lan','Linh','Long','Mai','Nam','Ngân','Phúc','Phương','Quân','Thảo','Trang','Trung'])[(((person_no - 1) / 96) % 24) + 1]
       END AS full_name
FROM numbered;

CREATE TEMP TABLE repaired_parent_names ON COMMIT DROP AS
WITH source AS (
  SELECT id, split_part(id, '-', 2)::int AS person_no
  FROM users
  WHERE role = 'PARENT' AND id ~ '^parent-[0-9]{4}$'
)
SELECT id,
       CASE WHEN id = 'parent-0001' THEN 'Nguyễn Văn Hùng' ELSE
         (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Vũ','Đặng','Đỗ','Bùi','Ngô','Dương','Đinh'])[((person_no + 116) % 12) + 1] || ' ' ||
         (ARRAY['Văn','Thị','Đức','Quốc','Minh','Ngọc','Thanh','Hoài'])[(((person_no + 116) / 12) % 8) + 1] || ' ' ||
         (ARRAY['An','Anh','Bình','Châu','Dũng','Giang','Hà','Hải','Hân','Hiếu','Hương','Khang','Lan','Linh','Long','Mai','Nam','Ngân','Phúc','Phương','Quân','Thảo','Trang','Trung'])[(((person_no + 116) / 96) % 24) + 1]
       END AS full_name
FROM source;

CREATE TEMP TABLE repaired_teacher_names ON COMMIT DROP AS
WITH source AS (
  SELECT id, split_part(id, '-', 2)::int AS teacher_no
  FROM users
  WHERE role = 'TEACHER' AND id ~ '^teacher-0(0[1-9]|[1-6][0-9]|7[0-2])$'
)
SELECT id,
       CASE WHEN id = 'teacher-001' THEN 'Nguyễn Đức Minh' ELSE
         (ARRAY['Nguyễn','Trần','Lê','Phạm','Hoàng','Vũ','Đặng','Đỗ','Bùi','Ngô','Dương','Đinh'])[((teacher_no - 1) % 12) + 1] || ' ' ||
         (ARRAY['Văn','Thị','Đức','Quốc','Minh','Ngọc'])[(((teacher_no - 1) / 12) % 6) + 1] || ' ' ||
         (ARRAY['Anh','Bình','Châu','Dũng','Giang','Hà','Hải','Hạnh','Hùng','Hương','Khánh','Lan','Linh','Long','Mai','Nam','Phương','Quân'])[(((teacher_no - 1) / 6) % 18) + 1]
       END AS full_name
FROM source;

DO $$
BEGIN
  IF (SELECT count(*) FROM repaired_student_names) <> (SELECT count(DISTINCT full_name) FROM repaired_student_names) THEN
    RAISE EXCEPTION 'Student name generator produced duplicates';
  END IF;
  IF (SELECT count(*) FROM repaired_parent_names) <> (SELECT count(DISTINCT full_name) FROM repaired_parent_names) THEN
    RAISE EXCEPTION 'Parent name generator produced duplicates';
  END IF;
  IF (SELECT count(*) FROM repaired_teacher_names) <> (SELECT count(DISTINCT full_name) FROM repaired_teacher_names) THEN
    RAISE EXCEPTION 'Teacher name generator produced duplicates';
  END IF;
END $$;

UPDATE users u SET full_name = n.full_name FROM repaired_student_names n WHERE u.id = n.id;
UPDATE users u SET full_name = n.full_name FROM repaired_parent_names n WHERE u.id = n.id;
UPDATE users u SET full_name = n.full_name FROM repaired_teacher_names n WHERE u.id = n.id;

-- Student snapshots.
UPDATE academic_documents x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE assignment_submissions x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE club_registrations x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE exam_candidates x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE exam_organization_plan_candidates x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE exam_review_requests x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE exam_seating_plan_items x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE invoices x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE leave_requests x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;
UPDATE student_yearly_summaries x SET student_name = n.full_name FROM repaired_student_names n WHERE x.student_id = n.id;

-- Teacher snapshots.
UPDATE assignments x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;
UPDATE classes x SET homeroom_teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.homeroom_teacher_id = n.id;
UPDATE exam_grading_assignments x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;
UPDATE exam_rooms x SET proctor_one_name = n.full_name FROM repaired_teacher_names n WHERE x.proctor_one_id = n.id;
UPDATE exam_rooms x SET proctor_two_name = n.full_name FROM repaired_teacher_names n WHERE x.proctor_two_id = n.id;
UPDATE exam_organization_plan_rooms x SET proctor_one_name = n.full_name FROM repaired_teacher_names n WHERE x.proctor_one_id = n.id;
UPDATE exam_organization_plan_rooms x SET proctor_two_name = n.full_name FROM repaired_teacher_names n WHERE x.proctor_two_id = n.id;
UPDATE exam_proctor_plan_items x SET previous_proctor_one_name = n.full_name FROM repaired_teacher_names n WHERE x.previous_proctor_one_id = n.id;
UPDATE exam_proctor_plan_items x SET previous_proctor_two_name = n.full_name FROM repaired_teacher_names n WHERE x.previous_proctor_two_id = n.id;
UPDATE exam_proctor_plan_items x SET proposed_proctor_one_name = n.full_name FROM repaired_teacher_names n WHERE x.proposed_proctor_one_id = n.id;
UPDATE exam_proctor_plan_items x SET proposed_proctor_two_name = n.full_name FROM repaired_teacher_names n WHERE x.proposed_proctor_two_id = n.id;
UPDATE leave_requests x SET homeroom_teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.homeroom_teacher_id = n.id;
UPDATE teacher_load_registrations x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;
UPDATE teaching_assignments x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;
UPDATE timetable_draft_slots x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;
UPDATE timetable_plan_slots x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;
UPDATE timetable_publication_slots x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;
UPDATE timetable_slots x SET teacher_name = n.full_name FROM repaired_teacher_names n WHERE x.teacher_id = n.id;

-- Cross-role snapshots and guardian information.
UPDATE leave_requests x SET parent_name = n.full_name FROM repaired_parent_names n WHERE x.parent_id = n.id;
UPDATE users student
SET guardian_name = parent_name.full_name
FROM parent_student relation
JOIN repaired_parent_names parent_name ON parent_name.id = relation.parent_id
WHERE student.id = relation.student_id AND student.role = 'STUDENT';
UPDATE chat_messages x SET sender_name = u.full_name FROM users u WHERE x.sender_id = u.id;
UPDATE chat_messages x SET recipient_name = u.full_name FROM users u WHERE x.recipient_id = u.id;
UPDATE audit_logs x SET actor_name = u.full_name FROM users u WHERE x.actor_id = u.id;
UPDATE operation_task_comments x SET author_name = u.full_name FROM users u WHERE x.author_id = u.id;
UPDATE operation_tasks x SET creator_name = u.full_name FROM users u WHERE x.created_by = u.id;
UPDATE operation_tasks x SET assigned_to_name = u.full_name FROM users u WHERE x.assigned_to = u.id;
UPDATE payments payment SET payer_name = parent_user.full_name
FROM invoices invoice JOIN users parent_user ON parent_user.id = invoice.parent_id
WHERE payment.invoice_id = invoice.id;

COMMIT;

-- Acceptance summary printed by psql.
SELECT role, count(*) AS total, count(DISTINCT full_name) AS distinct_names
FROM users
WHERE role IN ('STUDENT','PARENT','TEACHER')
GROUP BY role
ORDER BY role;
