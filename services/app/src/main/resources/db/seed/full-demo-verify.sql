\pset pager off

DO $$
DECLARE
    actual integer;
BEGIN
    SELECT count(*) INTO actual FROM roles
    WHERE code IN ('ADMIN','TEACHER','STUDENT','PARENT');
    IF actual<>4 THEN RAISE EXCEPTION 'Full Demo: four product roles are not configured'; END IF;

    SELECT count(*) INTO actual FROM academic_years
    WHERE code='2027-2028' AND status='ACTIVE'
      AND start_date=DATE '2027-09-01' AND end_date=DATE '2028-05-31';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: active academic year is invalid'; END IF;

    SELECT count(*) INTO actual FROM academic_years
    WHERE id='fd-ay-2026' AND status='CLOSED'
      AND start_date=DATE '2026-09-01' AND end_date=DATE '2027-05-31';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: closed rollover source year is invalid'; END IF;

    SELECT count(*) INTO actual FROM semesters
    WHERE academic_year_id='fd-ay-2026' AND status='CLOSED';
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: rollover source year must have two closed semesters'; END IF;

    SELECT count(*) INTO actual FROM semesters WHERE academic_year_id='fd-ay-2027';
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: expected 2 semesters, got %',actual; END IF;

    SELECT count(*) INTO actual FROM subjects
    WHERE code IN ('MATH','LIT','ENG','PHYS','CHEM','BIO','HIST','GEO','CIVIC','PE');
    IF actual<>10 THEN RAISE EXCEPTION 'Full Demo: requested subject catalog is incomplete'; END IF;

    SELECT count(*) INTO actual FROM subjects
    WHERE (id='sj-flag' AND code='CHAOCO')
       OR (id='sj-homeroom' AND code='SHL');
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: canonical fixed-activity subjects are missing'; END IF;

    SELECT count(*) INTO actual FROM classes WHERE academic_year_id='fd-ay-2027';
    IF actual<>30 THEN RAISE EXCEPTION 'Full Demo: expected 30 prepared classes, got %',actual; END IF;

    SELECT count(*) INTO actual FROM classes
    WHERE academic_year_id='fd-ay-2027' AND status='ACTIVE';
    IF actual<>6 THEN RAISE EXCEPTION 'Full Demo: expected 6 core active classes, got %',actual; END IF;

    SELECT count(*) INTO actual FROM classes
    WHERE academic_year_id='fd-ay-2027' AND status='INACTIVE';
    IF actual<>24 THEN RAISE EXCEPTION 'Full Demo: expected 24 reserve classes, got %',actual; END IF;

    SELECT count(*) INTO actual FROM rooms
    WHERE id LIKE 'fd-room-general-%' AND active=true;
    IF actual<>30 THEN RAISE EXCEPTION 'Full Demo: expected 30 dedicated classroom rooms, got %',actual; END IF;

    SELECT count(*) INTO actual FROM teacher_staffing_policies
    WHERE academic_year_id='fd-ay-2027' AND school_type='PUBLIC_REGULAR';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: teacher staffing policy is missing'; END IF;

    SELECT count(*) INTO actual FROM academic_promotion_policies
    WHERE academic_year_id='fd-ay-2027' AND minimum_conduct_grade='PASS';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: promotion policy is missing'; END IF;

    SELECT count(*) INTO actual FROM users WHERE id LIKE 'fd-student-%' AND status='ACTIVE';
    IF actual<>60 THEN RAISE EXCEPTION 'Full Demo: expected 60 active students, got %',actual; END IF;

    SELECT count(*) INTO actual FROM users WHERE id LIKE 'fd-teacher-%' AND status='ACTIVE';
    IF actual NOT BETWEEN 35 AND 45 THEN
        RAISE EXCEPTION 'Full Demo: active teacher count % is outside 35..45',actual;
    END IF;

    SELECT count(*) INTO actual FROM users WHERE id LIKE 'fd-parent-%' AND status='ACTIVE';
    IF actual NOT BETWEEN 45 AND 50 THEN
        RAISE EXCEPTION 'Full Demo: active parent count % is outside 45..50',actual;
    END IF;

    SELECT count(DISTINCT status) INTO actual FROM users
    WHERE id LIKE 'fd-%' AND status IN ('ACTIVE','LOCKED','PENDING','DELETED');
    IF actual<>4 THEN RAISE EXCEPTION 'Full Demo: account state coverage is incomplete'; END IF;

    SELECT count(*) INTO actual FROM student_class_enrollments
    WHERE academic_year_id='fd-ay-2027' AND status='ACTIVE';
    IF actual<>59 THEN RAISE EXCEPTION 'Full Demo: expected 59 active target-year enrollments plus one rollover candidate, got %',actual; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT class_id FROM student_class_enrollments
        WHERE academic_year_id='fd-ay-2027' AND status='ACTIVE'
        GROUP BY class_id HAVING count(*) NOT BETWEEN 9 AND 10
    ) invalid_class_size;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: current classes are outside expected 9..10 students'; END IF;

    SELECT count(*) INTO actual FROM student_class_enrollments
    WHERE academic_year_id='fd-ay-2026' AND class_id='fd-class-2026-11a1'
      AND student_id='fd-student-060' AND status='ACTIVE';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: year-end rollover source enrollment is missing'; END IF;

    SELECT count(*) INTO actual FROM parent_student WHERE student_id LIKE 'fd-student-%';
    IF actual<>60 THEN RAISE EXCEPTION 'Full Demo: expected 60 parent-child links, got %',actual; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT parent_id FROM parent_student GROUP BY parent_id HAVING count(*)>=2
    ) parents_with_two_children;
    IF actual<10 THEN RAISE EXCEPTION 'Full Demo: only % parents have 2 children',actual; END IF;

    SELECT count(*) INTO actual
    FROM teacher_class_subjects t
    WHERE t.status='ACTIVE' AND NOT EXISTS (
        SELECT 1 FROM teacher_subject_capabilities c
        WHERE c.teacher_id=t.teacher_id AND c.subject_id=t.subject_id AND c.active=true
    );
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: % teaching assignments violate capability',actual; END IF;

    SELECT count(*) INTO actual FROM education_programs
    WHERE id='fd-program-2027' AND status='ACTIVE';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: active education program is missing'; END IF;

    SELECT count(*) INTO actual FROM education_program_subjects
    WHERE program_id='fd-program-2027'
      AND annual_periods=semester1_periods+semester2_periods;
    IF actual<>36 THEN RAISE EXCEPTION 'Full Demo: program subject period totals are invalid'; END IF;

    SELECT count(*) INTO actual FROM class_subject_combinations
    WHERE class_id LIKE 'fd-class-%';
    IF actual<>30 THEN RAISE EXCEPTION 'Full Demo: expected one subject combination per prepared class'; END IF;

    SELECT count(*) INTO actual FROM academic_training_plans
    WHERE academic_year_id='fd-ay-2027' AND status='PUBLISHED';
    IF actual<>3 THEN RAISE EXCEPTION 'Full Demo: expected 3 published plans, got %',actual; END IF;

    SELECT count(*) INTO actual FROM academic_training_plans
    WHERE academic_year_id='fd-ay-2027' AND status='DRAFT' AND version_number=3;
    IF actual<>3 THEN RAISE EXCEPTION 'Full Demo: complete editable plan drafts are missing'; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT ps.id
        FROM academic_training_plan_subjects ps
        JOIN academic_training_plans p ON p.id=ps.plan_id AND p.version_number IN (2,3)
        LEFT JOIN academic_curriculum_distributions d ON d.plan_subject_id=ps.id
        WHERE p.academic_year_id='fd-ay-2027'
        GROUP BY ps.id,ps.total_periods
        HAVING coalesce(sum(d.periods),0)<>ps.total_periods
    ) invalid_distribution;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: % plan subjects have invalid weekly totals',actual; END IF;

    SELECT count(*) INTO actual
    FROM academic_assessment_plans a JOIN subjects s ON s.id=a.subject_id
    WHERE s.code IN ('CHAOCO','SHL');
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: fixed activities must not have seeded assessments'; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT ps.id
        FROM academic_training_plan_subjects ps
        JOIN academic_training_plans p ON p.id=ps.plan_id AND p.version_number IN (2,3)
        LEFT JOIN academic_assessment_plans a ON a.plan_id=ps.plan_id
            AND a.subject_id=ps.subject_id AND a.semester_id=ps.semester_id
        WHERE p.academic_year_id='fd-ay-2027' AND ps.exam_required=true
        GROUP BY ps.id HAVING count(a.id)<2
    ) missing_assessment_plan;
    IF actual<>0 THEN
        RAISE EXCEPTION 'Full Demo: % assessed plan subjects cannot pass publish preconditions',actual;
    END IF;

    SELECT count(*) INTO actual FROM (
        SELECT a.id
        FROM academic_assessment_plans a
        JOIN academic_training_plans p ON p.id=a.plan_id
        LEFT JOIN academic_assessment_plan_teachers t ON t.assessment_plan_id=a.id
        WHERE p.academic_year_id='fd-ay-2027'
        GROUP BY a.id HAVING count(t.id)<2 OR count(*) FILTER (WHERE t.primary_teacher)<>1
    ) invalid_responsible_teachers;
    IF actual<>0 THEN
        RAISE EXCEPTION 'Full Demo: % assessment plans do not have multiple responsible teachers',actual;
    END IF;

    SELECT count(*) INTO actual FROM timetable_slots WHERE semester_id='fd-sem-2027-1';
    IF actual<>132 THEN RAISE EXCEPTION 'Full Demo: expected 132 HK1 timetable slots, got %',actual; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT class_id,day_of_week
        FROM timetable_draft_slots
        WHERE schedule_id LIKE 'fd-schedule-%-hk1'
          AND assignment_id NOT LIKE 'activity-%'
        GROUP BY class_id,day_of_week HAVING count(*)<>4
    ) invalid_daily_load;
    IF actual<>0 THEN
        RAISE EXCEPTION 'Full Demo: % class/day groups do not have 4 regular lessons',actual;
    END IF;

    SELECT count(*) INTO actual FROM (
        SELECT class_id
        FROM timetable_draft_slots
        WHERE schedule_id LIKE 'fd-schedule-%-hk1'
          AND assignment_id LIKE 'activity-%'
        GROUP BY class_id HAVING count(*)<>2
    ) invalid_fixed_activities;
    IF actual<>0 THEN
        RAISE EXCEPTION 'Full Demo: fixed flag/homeroom activity coverage is invalid';
    END IF;

    SELECT count(*) INTO actual FROM (
        SELECT class_id,semester_id,day_of_week,period_no FROM timetable_slots
        GROUP BY class_id,semester_id,day_of_week,period_no HAVING count(*)>1
    ) conflict;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: class timetable collisions found'; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT teacher_id,semester_id,day_of_week,period_no FROM timetable_slots
        GROUP BY teacher_id,semester_id,day_of_week,period_no HAVING count(*)>1
    ) conflict;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: teacher timetable collisions found'; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT room_code,semester_id,day_of_week,period_no FROM timetable_slots
        GROUP BY room_code,semester_id,day_of_week,period_no HAVING count(*)>1
    ) conflict;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: room timetable collisions found'; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT c.grade_level
        FROM timetable_slots t JOIN classes c ON c.id=t.class_id
        WHERE t.semester_id='fd-sem-2027-1'
        GROUP BY c.grade_level HAVING count(*)<>44
    ) invalid_grade_schedule;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: timetable coverage is not 44 slots per grade'; END IF;

    SELECT count(*) INTO actual FROM class_lesson_progress
    WHERE id LIKE 'fd-progress-%' AND status='COMPLETED'
      AND source_plan_id='fd-plan-k10-v2';
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: comparable teaching progress is incomplete'; END IF;

    SELECT count(*) INTO actual FROM attendance_records
    WHERE status IN ('PRESENT','LATE','ABSENT_EXCUSED','ABSENT_UNEXCUSED');
    IF actual<4 THEN RAISE EXCEPTION 'Full Demo: attendance state coverage is incomplete'; END IF;

    SELECT count(*) INTO actual FROM attendance_excuse_requests
    WHERE id='fd-excuse-approved' AND status='APPROVED';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: approved attendance excuse is missing'; END IF;

    SELECT count(*) INTO actual FROM attendance_excuse_requests
    WHERE id='fd-excuse-pending' AND status='PENDING'
      AND student_id='fd-student-002' AND requested_by='fd-parent-001';
    IF actual<>1 THEN
        RAISE EXCEPTION 'Full Demo: parent pending attendance excuse is missing';
    END IF;

    SELECT count(*) INTO actual FROM grades WHERE id LIKE 'fd-grade-%';
    IF actual<700 THEN RAISE EXCEPTION 'Full Demo: grade coverage is incomplete'; END IF;

    SELECT count(*) INTO actual FROM grades
    WHERE student_id='fd-student-060'
      AND semester_id IN ('fd-sem-2026-1','fd-sem-2026-2');
    IF actual<>24 THEN RAISE EXCEPTION 'Full Demo: rollover candidate does not have 24 required grade entries'; END IF;

    SELECT count(*) INTO actual FROM attendance_records
    WHERE student_id='fd-student-060'
      AND id IN ('fd-prev-attendance-hk1-060','fd-prev-attendance-hk2-060');
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: rollover candidate attendance is incomplete'; END IF;

    SELECT count(*) INTO actual FROM student_yearly_summaries
    WHERE id='fd-year-summary-2026-060' AND academic_year_id='fd-ay-2026'
      AND class_id='fd-class-2026-11a1' AND status='DRAFT'
      AND conduct_grade='GOOD' AND result='PROMOTED';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: review-ready year summary is missing'; END IF;

    SELECT count(*) INTO actual FROM grade_change_logs
    WHERE id='fd-grade-log-001' AND old_score=8.4 AND new_score=9.2
      AND reason IS NOT NULL;
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: grade correction history is missing'; END IF;

    SELECT count(*) INTO actual FROM homeroom_remarks
    WHERE id='fd-homeroom-remark-001' AND status='PUBLISHED';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: published homeroom remark is missing'; END IF;

    SELECT count(*) INTO actual FROM assignments WHERE status='PUBLISHED';
    IF actual<1 THEN RAISE EXCEPTION 'Full Demo: no published assignment'; END IF;

    SELECT count(*) INTO actual FROM assignment_submissions
    WHERE status IN ('LATE','GRADED');
    IF actual<3 THEN RAISE EXCEPTION 'Full Demo: assignment submission states are incomplete'; END IF;

    SELECT count(*) INTO actual FROM assignments
    WHERE id IN ('fd-assignment-draft','fd-assignment-published')
      AND status IN ('DRAFT','PUBLISHED');
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: draft/published assignment coverage is incomplete'; END IF;

    SELECT count(*) INTO actual FROM exam_periods
    WHERE academic_year_id='fd-ay-2027' AND status='PUBLISHED';
    IF actual<1 THEN RAISE EXCEPTION 'Full Demo: no published exam period'; END IF;

    SELECT count(*) INTO actual FROM exam_room_students
    WHERE session_id IN (SELECT id FROM exam_sessions WHERE version_id='fd-exam-version-2');
    IF actual<>100 THEN RAISE EXCEPTION 'Full Demo: expected 100 exam seats, got %',actual; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT room.room_id,session.exam_date,session.start_time
        FROM exam_room_assignments room JOIN exam_sessions session ON session.id=room.session_id
        WHERE session.version_id='fd-exam-version-2'
        GROUP BY room.room_id,session.exam_date,session.start_time HAVING count(*)>1
    ) conflict;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: exam room collisions found'; END IF;

    SELECT count(*) INTO actual FROM (
        SELECT proctor_id,exam_date,start_time FROM (
            SELECT room.primary_proctor_id proctor_id,session.exam_date,session.start_time
            FROM exam_room_assignments room JOIN exam_sessions session ON session.id=room.session_id
            WHERE session.version_id='fd-exam-version-2'
            UNION ALL
            SELECT room.backup_proctor_id,session.exam_date,session.start_time
            FROM exam_room_assignments room JOIN exam_sessions session ON session.id=room.session_id
            WHERE session.version_id='fd-exam-version-2'
        ) proctors
        GROUP BY proctor_id,exam_date,start_time HAVING count(*)>1
    ) conflict;
    IF actual<>0 THEN RAISE EXCEPTION 'Full Demo: exam proctor collisions found'; END IF;

    SELECT count(*) INTO actual FROM fee_periods WHERE id LIKE 'fd-fee-%';
    IF actual<2 THEN RAISE EXCEPTION 'Full Demo: fee periods are incomplete'; END IF;

    SELECT count(DISTINCT status) INTO actual FROM invoices
    WHERE id LIKE 'fd-invoice-%' AND status IN ('PENDING','PARTIAL','PAID','OVERDUE','CANCELLED','VOID');
    IF actual<6 THEN RAISE EXCEPTION 'Full Demo: invoice state coverage is incomplete'; END IF;

    SELECT count(DISTINCT status) INTO actual FROM payments
    WHERE id LIKE 'fd-payment-%' AND status IN ('PENDING','SUCCESS','FAILED');
    IF actual<>3 THEN RAISE EXCEPTION 'Full Demo: payment state coverage is incomplete'; END IF;

    SELECT count(*) INTO actual FROM payment_refunds
    WHERE id='fd-refund-requested' AND payment_id='fd-payment-success'
      AND invoice_id='fd-invoice-003' AND student_id='fd-student-003'
      AND amount=300000 AND payment_amount=1200000
      AND refunded_amount_before=0 AND refund_type='PARTIAL'
      AND status='REQUESTED' AND requested_by='fd-admin-001';
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: review-ready payment refund is missing'; END IF;

    SELECT count(*) INTO actual FROM payment_receipts WHERE id LIKE 'fd-receipt-%';
    IF actual<2 THEN RAISE EXCEPTION 'Full Demo: payment receipts are incomplete'; END IF;

    SELECT count(*) INTO actual FROM payment_receipts r
    LEFT JOIN stored_files f ON f.id=r.file_id
    WHERE r.id LIKE 'fd-receipt-%' AND r.status='ISSUED'
      AND (r.file_id IS NULL OR f.id IS NULL OR f.status<>'READY');
    IF actual<>0 THEN
        RAISE EXCEPTION 'Full Demo: an issued receipt has no ready stored file';
    END IF;

    SELECT count(*) INTO actual FROM payment_reconciliation_runs
    WHERE id='fd-reconciliation-cash' AND status='BALANCED' AND discrepancy_count=0;
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: balanced payment reconciliation is missing'; END IF;

    SELECT count(*) INTO actual FROM notifications WHERE id LIKE 'fd-noti-%';
    IF actual<8 THEN RAISE EXCEPTION 'Full Demo: notifications are incomplete'; END IF;

    SELECT count(DISTINCT u.role) INTO actual
    FROM notifications n JOIN users u ON u.id=n.recipient_id
    WHERE n.id LIKE 'fd-noti-%' AND u.role IN ('ADMIN','TEACHER','STUDENT','PARENT');
    IF actual<>4 THEN RAISE EXCEPTION 'Full Demo: notifications do not cover all four roles'; END IF;

    SELECT count(*) INTO actual FROM announcements WHERE id LIKE 'fd-announcement-%';
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: announcements are incomplete'; END IF;

    SELECT count(*) INTO actual FROM chat_messages WHERE id LIKE 'fd-chat-%';
    IF actual<3 THEN RAISE EXCEPTION 'Full Demo: chat sample is incomplete'; END IF;

    SELECT count(*) INTO actual FROM clubs WHERE id LIKE 'fd-club-%' AND status='OPEN';
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: free/paid extracurricular clubs are incomplete'; END IF;

    SELECT count(*) INTO actual FROM user_devices WHERE id LIKE 'fd-device-%';
    IF actual<3 THEN RAISE EXCEPTION 'Full Demo: sample devices are incomplete'; END IF;

    SELECT count(*) INTO actual FROM refresh_tokens WHERE id='fd-refresh-revoked' AND revoked_at IS NOT NULL;
    IF actual<>1 THEN RAISE EXCEPTION 'Full Demo: sample revoked session is missing'; END IF;

    SELECT count(*) INTO actual FROM login_history WHERE id LIKE 'fd-login-%';
    IF actual<>2 THEN RAISE EXCEPTION 'Full Demo: login history samples are incomplete'; END IF;

    SELECT count(*) INTO actual FROM audit_logs
    WHERE id IN ('fd-audit-grade','fd-audit-payment','fd-audit-plan');
    IF actual<>3 THEN RAISE EXCEPTION 'Full Demo: important business audit samples are incomplete'; END IF;
END $$;

SELECT 'users' metric,count(*) value FROM users WHERE id LIKE 'fd-%'
UNION ALL SELECT 'active_teachers',count(*) FROM users WHERE id LIKE 'fd-teacher-%' AND status='ACTIVE'
UNION ALL SELECT 'active_students',count(*) FROM users WHERE id LIKE 'fd-student-%' AND status='ACTIVE'
UNION ALL SELECT 'active_parents',count(*) FROM users WHERE id LIKE 'fd-parent-%' AND status='ACTIVE'
UNION ALL SELECT 'classes',count(*) FROM classes WHERE academic_year_id='fd-ay-2027'
UNION ALL SELECT 'subjects',count(*) FROM subjects
UNION ALL SELECT 'teaching_assignments',count(*) FROM teacher_class_subjects
UNION ALL SELECT 'published_plans',count(*) FROM academic_training_plans WHERE status='PUBLISHED'
UNION ALL SELECT 'hk1_timetable_slots',count(*) FROM timetable_slots WHERE semester_id='fd-sem-2027-1'
UNION ALL SELECT 'grades',count(*) FROM grades
UNION ALL SELECT 'attendance',count(*) FROM attendance_records
UNION ALL SELECT 'assignments',count(*) FROM assignments
UNION ALL SELECT 'exam_sessions',count(*) FROM exam_sessions
UNION ALL SELECT 'invoices',count(*) FROM invoices
UNION ALL SELECT 'notifications',count(*) FROM notifications
UNION ALL SELECT 'chat_messages',count(*) FROM chat_messages
UNION ALL SELECT 'stored_files',count(*) FROM stored_files
ORDER BY metric;

SELECT 'FULL_DEMO_VALIDATION_PASSED' result;
