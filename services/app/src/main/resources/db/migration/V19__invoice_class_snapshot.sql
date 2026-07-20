ALTER TABLE invoices ADD COLUMN class_id varchar(255);
ALTER TABLE invoices ADD COLUMN class_code varchar(255);
ALTER TABLE invoices ADD COLUMN grade_level varchar(255);

UPDATE invoices
SET class_id = (
        SELECT users.class_id FROM users WHERE users.id = invoices.student_id
    )
WHERE class_id IS NULL;

UPDATE invoices
SET class_code = (
        SELECT classes.code FROM classes WHERE classes.id = invoices.class_id
    ),
    grade_level = (
        SELECT classes.grade_level FROM classes WHERE classes.id = invoices.class_id
    )
WHERE class_id IS NOT NULL;

CREATE INDEX idx_inv_class ON invoices (class_id);
CREATE INDEX idx_inv_grade ON invoices (grade_level);
