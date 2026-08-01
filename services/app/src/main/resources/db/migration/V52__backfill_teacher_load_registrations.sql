INSERT INTO teacher_load_registrations (
    id, teacher_id, teacher_name, semester_id, max_weekly_periods,
    unavailable_slots, preferred_grade_levels, note, review_note, status,
    submitted_at, reviewed_at, reviewed_by, created_at, updated_at
)
SELECT
    CONCAT('tlr-auto-', SUBSTRING(a.semester_id, 1, 20), '-', SUBSTRING(a.teacher_id, 1, 24)),
    a.teacher_id,
    MAX(a.teacher_name),
    a.semester_id,
    GREATEST(
        SUM(a.weekly_periods),
        COALESCE((SELECT MAX(existing.max_weekly_periods)
                  FROM teacher_load_registrations existing
                  WHERE existing.teacher_id = a.teacher_id), SUM(a.weekly_periods))
    ),
    NULL,
    NULL,
    'Tự động khởi tạo từ phân công giảng dạy hiện có',
    'Dữ liệu chuyển đổi đã được hệ thống đối chiếu',
    'APPROVED',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system-migration',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM teaching_assignments a
WHERE NOT EXISTS (
    SELECT 1 FROM teacher_load_registrations registration
    WHERE registration.teacher_id = a.teacher_id
      AND registration.semester_id = a.semester_id
)
GROUP BY a.teacher_id, a.semester_id;

