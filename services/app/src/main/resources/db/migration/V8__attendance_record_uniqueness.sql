ALTER TABLE attendance_records
    ADD CONSTRAINT uk_att_slot_date_student UNIQUE (slot_id, date, student_id);
