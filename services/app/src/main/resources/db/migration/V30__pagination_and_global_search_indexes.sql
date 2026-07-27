CREATE INDEX IF NOT EXISTS idx_users_role_status_name
    ON users(role, status, full_name, id);
CREATE INDEX IF NOT EXISTS idx_users_class_role_status
    ON users(class_id, role, status);
CREATE INDEX IF NOT EXISTS idx_users_full_name
    ON users(full_name);
CREATE INDEX IF NOT EXISTS idx_users_username
    ON users(username);
CREATE INDEX IF NOT EXISTS idx_classes_code_name
    ON classes(code, name);
CREATE INDEX IF NOT EXISTS idx_subjects_code_name
    ON subjects(code, name);
CREATE INDEX IF NOT EXISTS idx_assignments_title
    ON assignments(title);
CREATE INDEX IF NOT EXISTS idx_invoices_code_student
    ON invoices(code, student_name);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created
    ON notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_invoices_period_class_status
    ON invoices(fee_period_id, class_id, status);
