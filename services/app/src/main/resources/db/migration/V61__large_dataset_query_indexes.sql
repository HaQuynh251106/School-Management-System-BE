-- Cover the scoped reports and hierarchical report-card screens used with
-- thousands of students and hundreds of thousands of grade rows.
CREATE INDEX IF NOT EXISTS idx_grade_semester_student_score
    ON grades(semester_id, student_id, score);

CREATE INDEX IF NOT EXISTS idx_enrollment_class_student_status
    ON class_enrollments(class_id, student_id, status);

CREATE INDEX IF NOT EXISTS idx_report_card_year_class_status
    ON report_cards(academic_year_id, class_id, status);

CREATE INDEX IF NOT EXISTS idx_yearly_summary_year_class_student
    ON student_yearly_summaries(academic_year_id, class_id, student_id);

CREATE INDEX IF NOT EXISTS idx_attendance_class_date_status
    ON attendance_records(class_id, date, status);

