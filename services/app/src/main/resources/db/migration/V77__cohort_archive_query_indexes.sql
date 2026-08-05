CREATE INDEX IF NOT EXISTS idx_users_cohort_role_status
    ON users(cohort_id, role, student_status, full_name, id);

CREATE INDEX IF NOT EXISTS idx_enrollments_cohort_student_year
    ON class_enrollments(cohort_id, student_id, academic_year_id, enrolled_at);

CREATE INDEX IF NOT EXISTS idx_yearly_summary_student_year_result
    ON student_yearly_summaries(student_id, academic_year_id, promotion_status, conduct_grade);

CREATE INDEX IF NOT EXISTS idx_report_cards_student_year_status
    ON report_cards(student_id, academic_year_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_attendance_student_date_status
    ON attendance_records(student_id, date, status);
