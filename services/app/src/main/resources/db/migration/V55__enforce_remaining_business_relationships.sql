-- Complete relational integrity for business tables added after the baseline.
-- Business data is owned by PostgreSQL; application code only reads/writes it
-- through repositories and APIs. Polymorphic target_id/ref_id columns are
-- intentionally excluded because one column can reference several tables.

-- Delivery attempts may outlive a notification in older databases. They have
-- no independent business meaning, so remove only those orphaned attempts.
DELETE FROM notification_delivery_logs log
WHERE NOT EXISTS (
    SELECT 1 FROM notifications notification
    WHERE notification.id = log.notification_id
);

-- A NULL actor denotes an automated system action. Older rows used the text
-- sentinel "SYSTEM", which is not (and must not become) a fake user record.
UPDATE student_class_enrollments
SET enrolled_by = NULL
WHERE enrolled_by = 'SYSTEM';

ALTER TABLE academic_promotion_policies
    ADD CONSTRAINT fk_promotion_policy_year
        FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
    ADD CONSTRAINT fk_promotion_policy_updated_by
        FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE academic_result_locks
    ADD CONSTRAINT fk_result_lock_year
        FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
    ADD CONSTRAINT fk_result_lock_class
        FOREIGN KEY (class_id) REFERENCES classes(id),
    ADD CONSTRAINT fk_result_lock_semester
        FOREIGN KEY (semester_id) REFERENCES semesters(id),
    ADD CONSTRAINT fk_result_lock_user
        FOREIGN KEY (locked_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE assignments
    ADD CONSTRAINT fk_assignment_attachment
        FOREIGN KEY (attachment_file_id) REFERENCES stored_files(id) ON DELETE SET NULL;

ALTER TABLE assignment_submissions
    ADD CONSTRAINT fk_submission_attachment
        FOREIGN KEY (attachment_file_id) REFERENCES stored_files(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_submission_graded_by
        FOREIGN KEY (graded_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE assignment_submission_versions
    ADD CONSTRAINT fk_submission_version_submission
        FOREIGN KEY (submission_id) REFERENCES assignment_submissions(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_submission_version_attachment
        FOREIGN KEY (attachment_file_id) REFERENCES stored_files(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_submission_version_actor
        FOREIGN KEY (submitted_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE submission_resubmission_requests
    ADD CONSTRAINT fk_resubmission_assignment
        FOREIGN KEY (assignment_id) REFERENCES assignments(id),
    ADD CONSTRAINT fk_resubmission_student
        FOREIGN KEY (student_id) REFERENCES users(id),
    ADD CONSTRAINT fk_resubmission_submission
        FOREIGN KEY (submission_id) REFERENCES assignment_submissions(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_resubmission_actor
        FOREIGN KEY (requested_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE attendance_excuse_requests
    ADD CONSTRAINT fk_excuse_attendance
        FOREIGN KEY (attendance_record_id) REFERENCES attendance_records(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_excuse_student
        FOREIGN KEY (student_id) REFERENCES users(id),
    ADD CONSTRAINT fk_excuse_requested_by
        FOREIGN KEY (requested_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_excuse_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE grade_configurations
    ADD CONSTRAINT fk_grade_configuration_subject
        FOREIGN KEY (subject_id) REFERENCES subjects(id),
    ADD CONSTRAINT fk_grade_configuration_semester
        FOREIGN KEY (semester_id) REFERENCES semesters(id),
    ADD CONSTRAINT fk_grade_configuration_updated_by
        FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE exam_schedule_versions
    ADD CONSTRAINT fk_exam_version_base
        FOREIGN KEY (based_on_version_id) REFERENCES exam_schedule_versions(id) ON DELETE SET NULL;

ALTER TABLE club_registrations
    ADD CONSTRAINT fk_club_registration_club
        FOREIGN KEY (club_id) REFERENCES clubs(id),
    ADD CONSTRAINT fk_club_registration_student
        FOREIGN KEY (student_id) REFERENCES users(id),
    ADD CONSTRAINT fk_club_registration_actor
        FOREIGN KEY (registered_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_club_registration_fee_period
        FOREIGN KEY (fee_period_id) REFERENCES fee_periods(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_club_registration_invoice
        FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE SET NULL;

ALTER TABLE fee_periods
    ADD CONSTRAINT fk_fee_period_year
        FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
    ADD CONSTRAINT fk_fee_period_semester
        FOREIGN KEY (semester_id) REFERENCES semesters(id);

ALTER TABLE fee_period_items
    ADD CONSTRAINT fk_fee_item_period
        FOREIGN KEY (fee_period_id) REFERENCES fee_periods(id) ON DELETE CASCADE;

ALTER TABLE invoices
    ADD CONSTRAINT fk_invoice_fee_period
        FOREIGN KEY (fee_period_id) REFERENCES fee_periods(id),
    ADD CONSTRAINT fk_invoice_parent
        FOREIGN KEY (parent_id) REFERENCES users(id),
    ADD CONSTRAINT fk_invoice_student
        FOREIGN KEY (student_id) REFERENCES users(id);

ALTER TABLE invoice_items
    ADD CONSTRAINT fk_invoice_item_fee_item
        FOREIGN KEY (fee_period_item_id) REFERENCES fee_period_items(id) ON DELETE SET NULL;

ALTER TABLE bank_statement_entries
    ADD CONSTRAINT fk_statement_invoice
        FOREIGN KEY (matched_invoice_id) REFERENCES invoices(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_statement_payment
        FOREIGN KEY (matched_payment_id) REFERENCES payments(id) ON DELETE SET NULL;

ALTER TABLE payment_proofs
    ADD CONSTRAINT fk_payment_proof_parent
        FOREIGN KEY (parent_id) REFERENCES users(id),
    ADD CONSTRAINT fk_payment_proof_student
        FOREIGN KEY (student_id) REFERENCES users(id);

ALTER TABLE payment_receipts
    ADD CONSTRAINT fk_payment_receipt_parent
        FOREIGN KEY (parent_id) REFERENCES users(id),
    ADD CONSTRAINT fk_payment_receipt_student
        FOREIGN KEY (student_id) REFERENCES users(id),
    ADD CONSTRAINT fk_payment_receipt_previous
        FOREIGN KEY (previous_file_id) REFERENCES stored_files(id) ON DELETE SET NULL;

ALTER TABLE payment_refunds
    ADD CONSTRAINT fk_payment_refund_parent
        FOREIGN KEY (parent_id) REFERENCES users(id),
    ADD CONSTRAINT fk_payment_refund_student
        FOREIGN KEY (student_id) REFERENCES users(id);

ALTER TABLE notification_delivery_logs
    ADD CONSTRAINT fk_notification_delivery_notification
        FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE;

ALTER TABLE user_notification_preferences
    ADD CONSTRAINT fk_notification_preference_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_replaced_by
        FOREIGN KEY (replaced_by_token_id) REFERENCES refresh_tokens(id) ON DELETE SET NULL;

ALTER TABLE student_class_enrollments
    ADD CONSTRAINT fk_enrollment_year
        FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
    ADD CONSTRAINT fk_enrollment_class
        FOREIGN KEY (class_id) REFERENCES classes(id),
    ADD CONSTRAINT fk_enrollment_student
        FOREIGN KEY (student_id) REFERENCES users(id),
    ADD CONSTRAINT fk_enrollment_source_year
        FOREIGN KEY (source_academic_year_id) REFERENCES academic_years(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_enrollment_source_class
        FOREIGN KEY (source_class_id) REFERENCES classes(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_enrollment_source_summary
        FOREIGN KEY (source_summary_id) REFERENCES student_yearly_summaries(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_enrollment_actor
        FOREIGN KEY (enrolled_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_enrollment_reverted_by
        FOREIGN KEY (reverted_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE student_yearly_summaries
    ADD CONSTRAINT fk_yearly_summary_year
        FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
    ADD CONSTRAINT fk_yearly_summary_class
        FOREIGN KEY (class_id) REFERENCES classes(id),
    ADD CONSTRAINT fk_yearly_summary_student
        FOREIGN KEY (student_id) REFERENCES users(id),
    ADD CONSTRAINT fk_yearly_summary_next_class
        FOREIGN KEY (next_class_id) REFERENCES classes(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_yearly_summary_finalized_by
        FOREIGN KEY (finalized_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_yearly_summary_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_yearly_summary_progressed_by
        FOREIGN KEY (progressed_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE year_result_publications
    ADD CONSTRAINT fk_year_publication_year
        FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
    ADD CONSTRAINT fk_year_publication_class
        FOREIGN KEY (class_id) REFERENCES classes(id),
    ADD CONSTRAINT fk_year_publication_published_by
        FOREIGN KEY (published_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_year_publication_withdrawn_by
        FOREIGN KEY (withdrawn_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE year_result_publication_history
    ADD CONSTRAINT fk_year_history_publication
        FOREIGN KEY (publication_id) REFERENCES year_result_publications(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_year_history_year
        FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
    ADD CONSTRAINT fk_year_history_class
        FOREIGN KEY (class_id) REFERENCES classes(id),
    ADD CONSTRAINT fk_year_history_actor
        FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL;
