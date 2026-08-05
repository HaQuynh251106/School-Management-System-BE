\pset pager off

WITH issues AS (
    SELECT 'duplicate_username_ci' AS issue, count(*)::bigint AS affected
    FROM (
        SELECT lower(username)
        FROM users
        GROUP BY lower(username)
        HAVING count(*) > 1
    ) x
    UNION ALL
    SELECT 'duplicate_email_ci', count(*) FROM (
        SELECT lower(email)
        FROM users
        WHERE nullif(trim(email), '') IS NOT NULL
        GROUP BY lower(email)
        HAVING count(*) > 1
    ) x
    UNION ALL
    SELECT 'duplicate_student_code_ci', count(*) FROM (
        SELECT lower(student_code)
        FROM users
        WHERE nullif(trim(student_code), '') IS NOT NULL
        GROUP BY lower(student_code)
        HAVING count(*) > 1
    ) x
    UNION ALL
    SELECT 'duplicate_teacher_code_ci', count(*) FROM (
        SELECT lower(teacher_code)
        FROM users
        WHERE nullif(trim(teacher_code), '') IS NOT NULL
        GROUP BY lower(teacher_code)
        HAVING count(*) > 1
    ) x
    UNION ALL
    SELECT 'invalid_user_role', count(*) FROM users
    WHERE role NOT IN ('ADMIN', 'TEACHER', 'STUDENT', 'PARENT')
    UNION ALL
    SELECT 'invalid_user_status', count(*) FROM users
    WHERE status NOT IN ('ACTIVE', 'LOCKED', 'PENDING', 'DELETED')
    UNION ALL
    SELECT 'student_missing_class', count(*) FROM users
    WHERE role = 'STUDENT' AND status <> 'DELETED' AND class_id IS NULL
    UNION ALL
    SELECT 'student_orphan_class', count(*) FROM users u
    WHERE u.role = 'STUDENT' AND u.class_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM classes c WHERE c.id = u.class_id)
    UNION ALL
    SELECT 'class_student_count_mismatch', count(*) FROM (
        SELECT c.id
        FROM classes c
        LEFT JOIN student_class_enrollments enrollment
          ON enrollment.class_id = c.id
         AND enrollment.academic_year_id = c.academic_year_id
        GROUP BY c.id, c.student_count
        HAVING c.student_count <> count(enrollment.id)
    ) x
    UNION ALL
    SELECT 'academic_grade_catalog_invalid',
        CASE WHEN array_agg(code::text ORDER BY code)
            = ARRAY['K10', 'K11', 'K12']::text[]
            THEN 0 ELSE 1 END
    FROM grade_levels
    UNION ALL
    SELECT 'academic_year_invalid_dates', count(*) FROM academic_years
    WHERE end_date <= start_date
    UNION ALL
    SELECT 'academic_year_active_count_invalid',
        CASE WHEN count(*) = 1 THEN 0 ELSE 1 END
    FROM academic_years
    WHERE status = 'ACTIVE'
    UNION ALL
    SELECT 'academic_year_semester_count_invalid', count(*) FROM (
        SELECT ay.id
        FROM academic_years ay
        LEFT JOIN semesters s ON s.academic_year_id = ay.id
        GROUP BY ay.id
        HAVING count(s.id) <> 2
    ) x
    UNION ALL
    SELECT 'semester_invalid_range', count(*) FROM semesters s
    JOIN academic_years ay ON ay.id = s.academic_year_id
    WHERE s.sequence NOT IN (1, 2)
       OR s.end_date <= s.start_date
       OR s.start_date < ay.start_date
       OR s.end_date > ay.end_date
    UNION ALL
    SELECT 'semester_fixed_calendar_invalid', count(*) FROM semesters s
    JOIN academic_years ay ON ay.id = s.academic_year_id
    WHERE ay.code ~ '^[0-9]{4}-[0-9]{4}$'
      AND (
        (s.code = 'HK1' AND (
            s.start_date <> make_date(substring(ay.code, 1, 4)::integer, 9, 1)
            OR s.end_date <> make_date(substring(ay.code, 1, 4)::integer + 1, 1, 31)
        ))
        OR
        (s.code = 'HK2' AND (
            s.start_date <> make_date(substring(ay.code, 1, 4)::integer + 1, 2, 1)
            OR s.end_date <> make_date(substring(ay.code, 1, 4)::integer + 1, 6, 30)
        ))
      )
    UNION ALL
    SELECT 'class_invalid_grade', count(*) FROM classes
    WHERE grade_level NOT IN ('K10', 'K11', 'K12')
    UNION ALL
    SELECT 'enrollment_orphan_student', count(*)
    FROM student_class_enrollments e
    WHERE NOT EXISTS (
        SELECT 1 FROM users u
        WHERE u.id = e.student_id AND u.role = 'STUDENT'
    )
    UNION ALL
    SELECT 'enrollment_class_year_mismatch', count(*)
    FROM student_class_enrollments e
    JOIN classes c ON c.id = e.class_id
    WHERE c.academic_year_id <> e.academic_year_id
    UNION ALL
    SELECT 'training_plan_invalid_scope', count(*)
    FROM academic_training_plans p
    WHERE p.grade_level NOT IN ('K10', 'K11', 'K12')
       OR p.status NOT IN ('DRAFT', 'PUBLISHED', 'LOCKED')
       OR p.version_number < 1
       OR NOT EXISTS (
           SELECT 1 FROM academic_years ay WHERE ay.id = p.academic_year_id
       )
    UNION ALL
    SELECT 'training_plan_subject_invalid_scope', count(*)
    FROM academic_training_plan_subjects ps
    JOIN academic_training_plans p ON p.id = ps.plan_id
    JOIN semesters s ON s.id = ps.semester_id
    WHERE s.academic_year_id <> p.academic_year_id
       OR ps.start_date < s.start_date
       OR ps.end_date > s.end_date
       OR ps.end_date < ps.start_date
       OR ps.weekly_periods <= 0
       OR ps.total_periods <= 0
    UNION ALL
    SELECT 'training_plan_duplicate_version', count(*)
    FROM (
        SELECT academic_year_id, grade_level, version_number
        FROM academic_training_plans
        GROUP BY academic_year_id, grade_level, version_number
        HAVING count(*) > 1
    ) duplicate
    UNION ALL
    SELECT 'training_plan_multiple_published', count(*)
    FROM (
        SELECT academic_year_id, grade_level
        FROM academic_training_plans
        WHERE status = 'PUBLISHED'
        GROUP BY academic_year_id, grade_level
        HAVING count(*) > 1
    ) duplicate
    UNION ALL
    SELECT 'training_stage_invalid_scope', count(*)
    FROM academic_training_plan_stages stage
    JOIN academic_training_plan_subjects subject
      ON subject.id = stage.plan_subject_id
    WHERE stage.start_date < subject.start_date
       OR stage.end_date > subject.end_date
       OR stage.end_date < stage.start_date
       OR stage.target_periods <= 0
    UNION ALL
    SELECT 'curriculum_invalid_hierarchy', count(*)
    FROM academic_curriculum_items item
    LEFT JOIN academic_curriculum_items parent ON parent.id = item.parent_id
    WHERE (item.item_type = 'CHAPTER' AND item.parent_id IS NOT NULL)
       OR (item.item_type = 'TOPIC'
           AND (parent.item_type IS DISTINCT FROM 'CHAPTER'
                OR parent.plan_subject_id <> item.plan_subject_id))
       OR (item.item_type = 'LESSON'
           AND (parent.item_type IS DISTINCT FROM 'TOPIC'
                OR parent.plan_subject_id <> item.plan_subject_id))
       OR item.planned_periods < 0
    UNION ALL
    SELECT 'training_special_week_invalid', count(*)
    FROM academic_training_plan_special_weeks week
    WHERE week.week_type NOT IN ('EXAM', 'BUFFER')
       OR week.week_number NOT BETWEEN 1 AND 30
    UNION ALL
    SELECT 'exam_schedule_invalid_scope', count(*)
    FROM academic_exam_schedules e
    JOIN academic_training_plans p ON p.id = e.plan_id
    JOIN semesters s ON s.id = e.semester_id
    WHERE s.academic_year_id <> p.academic_year_id
       OR e.exam_date < s.start_date
       OR e.exam_date > s.end_date
       OR e.duration_minutes <= 0
       OR NOT EXISTS (
           SELECT 1
           FROM academic_training_plan_subjects ps
           WHERE ps.plan_id = e.plan_id
             AND ps.semester_id = e.semester_id
             AND ps.subject_id = e.subject_id
       )
    UNION ALL
    SELECT 'parent_relation_orphan_parent', count(*) FROM parent_student r
    WHERE NOT EXISTS (
        SELECT 1 FROM users u WHERE u.id = r.parent_id AND u.role = 'PARENT'
    )
    UNION ALL
    SELECT 'parent_relation_orphan_student', count(*) FROM parent_student r
    WHERE NOT EXISTS (
        SELECT 1 FROM users u WHERE u.id = r.student_id AND u.role = 'STUDENT'
    )
    UNION ALL
    SELECT 'grade_orphan_student', count(*) FROM grades g
    WHERE NOT EXISTS (
        SELECT 1 FROM users u WHERE u.id = g.student_id AND u.role = 'STUDENT'
    )
    UNION ALL
    SELECT 'grade_orphan_subject', count(*) FROM grades g
    WHERE NOT EXISTS (SELECT 1 FROM subjects s WHERE s.id = g.subject_id)
    UNION ALL
    SELECT 'grade_orphan_semester', count(*) FROM grades g
    WHERE NOT EXISTS (SELECT 1 FROM semesters s WHERE s.id = g.semester_id)
    UNION ALL
    SELECT 'grade_invalid_score', count(*) FROM grades
    WHERE score IS NOT NULL AND (score < 0 OR score > 10)
    UNION ALL
    SELECT 'teacher_assignment_orphan_teacher', count(*) FROM teacher_class_subjects x
    WHERE NOT EXISTS (
        SELECT 1 FROM users u WHERE u.id = x.teacher_id AND u.role = 'TEACHER'
    )
    UNION ALL
    SELECT 'teacher_assignment_orphan_class', count(*) FROM teacher_class_subjects x
    WHERE NOT EXISTS (SELECT 1 FROM classes c WHERE c.id = x.class_id)
    UNION ALL
    SELECT 'teacher_assignment_orphan_subject', count(*) FROM teacher_class_subjects x
    WHERE NOT EXISTS (SELECT 1 FROM subjects s WHERE s.id = x.subject_id)
    UNION ALL
    SELECT 'teacher_assignment_orphan_semester', count(*) FROM teacher_class_subjects x
    WHERE NOT EXISTS (SELECT 1 FROM semesters s WHERE s.id = x.semester_id)
    UNION ALL
    SELECT 'timetable_orphan_class', count(*) FROM timetable_slots x
    WHERE x.class_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM classes c WHERE c.id = x.class_id)
    UNION ALL
    SELECT 'timetable_orphan_teacher', count(*) FROM timetable_slots x
    WHERE x.teacher_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = x.teacher_id)
    UNION ALL
    SELECT 'timetable_invalid_period', count(*) FROM timetable_slots
    WHERE period_no NOT BETWEEN 1 AND 12
    UNION ALL
    SELECT 'subject_invalid_required_room_type', count(*) FROM subjects
    WHERE required_room_type NOT IN ('GENERAL', 'LAB', 'COMPUTER', 'GYM', 'MUSIC', 'ART')
    UNION ALL
    SELECT 'room_invalid_type', count(*) FROM rooms
    WHERE room_type NOT IN ('GENERAL', 'LAB', 'COMPUTER', 'GYM', 'MUSIC', 'ART')
    UNION ALL
    SELECT 'timetable_schedule_invalid_scope', count(*) FROM timetable_schedules x
    WHERE x.scope_grade_level IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM grade_levels g WHERE g.code = x.scope_grade_level)
    UNION ALL
    SELECT 'timetable_draft_assignment_coverage_mismatch', count(*) FROM (
        SELECT d.schedule_id, d.assignment_id
        FROM timetable_draft_slots d
        JOIN teacher_class_subjects assignment ON assignment.id = d.assignment_id
        GROUP BY d.schedule_id, d.assignment_id, assignment.weekly_periods
        HAVING count(*) <> greatest(1, coalesce(assignment.weekly_periods, 1))
    ) x
    UNION ALL
    SELECT 'timetable_draft_room_type_mismatch', count(*)
    FROM timetable_draft_slots d
    JOIN timetable_schedules schedule ON schedule.id = d.schedule_id
    JOIN subjects subject ON subject.id = d.subject_id
    JOIN rooms room ON room.id = d.room_id
    WHERE schedule.status IN ('PUBLISHED', 'LOCKED')
      AND subject.required_room_type <> 'GENERAL'
      AND subject.required_room_type <> room.room_type
    UNION ALL
    SELECT 'lesson_progress_invalid_teacher_scope', count(*)
    FROM class_lesson_progress progress
    WHERE NOT EXISTS (
        SELECT 1 FROM teacher_class_subjects assignment
        WHERE assignment.teacher_id = progress.teacher_id
          AND assignment.class_id = progress.class_id
          AND assignment.subject_id = progress.subject_id
          AND assignment.semester_id = progress.semester_id
          AND assignment.status = 'ACTIVE'
    )
    UNION ALL
    SELECT 'makeup_approved_without_time', count(*) FROM timetable_makeup_proposals
    WHERE status = 'APPROVED'
      AND (proposed_date IS NULL OR proposed_period_no IS NULL)
    UNION ALL
    SELECT 'attendance_orphan_student', count(*) FROM attendance_records x
    WHERE x.student_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = x.student_id)
    UNION ALL
    SELECT 'attendance_orphan_class', count(*) FROM attendance_records x
    WHERE x.class_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM classes c WHERE c.id = x.class_id)
    UNION ALL
    SELECT 'assignment_orphan_teacher', count(*) FROM assignments x
    WHERE x.teacher_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = x.teacher_id)
    UNION ALL
    SELECT 'assignment_orphan_class', count(*) FROM assignments x
    WHERE x.class_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM classes c WHERE c.id = x.class_id)
    UNION ALL
    SELECT 'submission_orphan_assignment', count(*) FROM assignment_submissions x
    WHERE x.assignment_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM assignments a WHERE a.id = x.assignment_id)
    UNION ALL
    SELECT 'submission_orphan_student', count(*) FROM assignment_submissions x
    WHERE x.student_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = x.student_id)
    UNION ALL
    SELECT 'invoice_orphan_student', count(*) FROM invoices x
    WHERE x.student_id IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM users u WHERE u.id = x.student_id AND u.role = 'STUDENT'
      )
    UNION ALL
    SELECT 'payment_orphan_invoice', count(*) FROM payments x
    WHERE x.invoice_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM invoices i WHERE i.id = x.invoice_id)
    UNION ALL
    SELECT 'refresh_token_orphan_user', count(*) FROM refresh_tokens x
    WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = x.user_id)
    UNION ALL
    SELECT 'device_orphan_user', count(*) FROM user_devices x
    WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = x.user_id)
)
SELECT issue, affected
FROM issues
ORDER BY issue;

WITH totals AS (
    SELECT
        (SELECT count(*) FROM users) AS users,
        (SELECT count(*) FROM users WHERE role = 'TEACHER') AS teachers,
        (SELECT count(*) FROM users WHERE role = 'STUDENT') AS students,
        (SELECT count(*) FROM users WHERE role = 'PARENT') AS parents,
        (SELECT count(*) FROM classes) AS classes,
        (SELECT count(*) FROM student_class_enrollments) AS enrollments,
        (SELECT count(*) FROM academic_training_plans) AS training_plans,
        (SELECT count(*) FROM grades) AS grades,
        (SELECT count(*) FROM attendance_records) AS attendance_records,
        (SELECT count(*) FROM invoices) AS invoices
)
SELECT * FROM totals;
