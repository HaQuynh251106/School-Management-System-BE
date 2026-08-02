-- Business identifiers are used by import, search and integrations and must
-- not silently point to multiple accounts. NULL remains allowed.
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_student_code ON users(student_code);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_teacher_code ON users(teacher_code);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email ON users(email);

