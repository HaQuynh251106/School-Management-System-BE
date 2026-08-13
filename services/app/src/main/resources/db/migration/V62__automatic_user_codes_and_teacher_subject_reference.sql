ALTER TABLE users ADD COLUMN IF NOT EXISTS user_code VARCHAR(20);

-- Assign stable, role-prefixed business codes to every existing account.
UPDATE users AS account
SET user_code = CASE account.role
    WHEN 'ADMIN' THEN CONCAT('AD', LPAD(CAST((
        SELECT COUNT(*) FROM users ranked
        WHERE ranked.role = 'ADMIN' AND ranked.id <= account.id
    ) AS VARCHAR), 6, '0'))
    WHEN 'ACADEMIC_STAFF' THEN CONCAT('GVU', LPAD(CAST((
        SELECT COUNT(*) FROM users ranked
        WHERE ranked.role = 'ACADEMIC_STAFF' AND ranked.id <= account.id
    ) AS VARCHAR), 6, '0'))
    WHEN 'ACCOUNTANT' THEN CONCAT('KT', LPAD(CAST((
        SELECT COUNT(*) FROM users ranked
        WHERE ranked.role = 'ACCOUNTANT' AND ranked.id <= account.id
    ) AS VARCHAR), 6, '0'))
    WHEN 'TEACHER' THEN CONCAT('GV', LPAD(CAST((
        SELECT COUNT(*) FROM users ranked
        WHERE ranked.role = 'TEACHER' AND ranked.id <= account.id
    ) AS VARCHAR), 6, '0'))
    WHEN 'STUDENT' THEN CONCAT('HS', LPAD(CAST((
        SELECT COUNT(*) FROM users ranked
        WHERE ranked.role = 'STUDENT' AND ranked.id <= account.id
    ) AS VARCHAR), 6, '0'))
    WHEN 'PARENT' THEN CONCAT('PH', LPAD(CAST((
        SELECT COUNT(*) FROM users ranked
        WHERE ranked.role = 'PARENT' AND ranked.id <= account.id
    ) AS VARCHAR), 6, '0'))
END;

UPDATE users SET teacher_code = user_code WHERE role = 'TEACHER';
UPDATE users SET student_code = user_code WHERE role = 'STUDENT';

ALTER TABLE users ALTER COLUMN user_code SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_user_code ON users (user_code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_student_code ON users (student_code);

INSERT INTO business_code_counters (code_type, next_value)
SELECT 'ADMIN', 1 WHERE NOT EXISTS (
    SELECT 1 FROM business_code_counters WHERE code_type = 'ADMIN'
);
INSERT INTO business_code_counters (code_type, next_value)
SELECT 'ACADEMIC_STAFF', 1 WHERE NOT EXISTS (
    SELECT 1 FROM business_code_counters WHERE code_type = 'ACADEMIC_STAFF'
);
INSERT INTO business_code_counters (code_type, next_value)
SELECT 'ACCOUNTANT', 1 WHERE NOT EXISTS (
    SELECT 1 FROM business_code_counters WHERE code_type = 'ACCOUNTANT'
);
INSERT INTO business_code_counters (code_type, next_value)
SELECT 'STUDENT', 1 WHERE NOT EXISTS (
    SELECT 1 FROM business_code_counters WHERE code_type = 'STUDENT'
);
INSERT INTO business_code_counters (code_type, next_value)
SELECT 'PARENT', 1 WHERE NOT EXISTS (
    SELECT 1 FROM business_code_counters WHERE code_type = 'PARENT'
);

UPDATE business_code_counters AS counters
SET next_value = (
    SELECT COUNT(*) + 1 FROM users WHERE role = counters.code_type
)
WHERE counters.code_type IN (
    'ADMIN', 'ACADEMIC_STAFF', 'ACCOUNTANT', 'TEACHER', 'STUDENT', 'PARENT'
);

UPDATE users AS teacher
SET main_subject_id = (
    SELECT subject.id FROM subjects subject
    WHERE LOWER(subject.name) = LOWER(teacher.main_subject)
)
WHERE teacher.role = 'TEACHER'
  AND teacher.main_subject_id IS NULL
  AND EXISTS (
      SELECT 1 FROM subjects subject
      WHERE LOWER(subject.name) = LOWER(teacher.main_subject)
  );

ALTER TABLE users ADD CONSTRAINT fk_users_main_subject
    FOREIGN KEY (main_subject_id) REFERENCES subjects(id);
