CREATE TABLE IF NOT EXISTS business_code_counters (
    code_type VARCHAR(50) PRIMARY KEY,
    next_value BIGINT NOT NULL
);

INSERT INTO business_code_counters (code_type, next_value)
SELECT 'TEACHER', 1
WHERE NOT EXISTS (
    SELECT 1 FROM business_code_counters WHERE code_type = 'TEACHER'
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_teacher_code
    ON users (teacher_code);
