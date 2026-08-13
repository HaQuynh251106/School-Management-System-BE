UPDATE users
SET teacher_code = CONCAT('GV000', SUBSTRING(teacher_code, 3))
WHERE role = 'TEACHER'
  AND LENGTH(teacher_code) = 5
  AND teacher_code LIKE 'GV___'
  AND NOT EXISTS (
      SELECT 1
      FROM users existing
      WHERE existing.id <> users.id
        AND existing.teacher_code = CONCAT('GV000', SUBSTRING(users.teacher_code, 3))
  );
