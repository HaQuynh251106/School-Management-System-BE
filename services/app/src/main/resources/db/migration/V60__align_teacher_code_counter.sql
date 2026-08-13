UPDATE business_code_counters
SET next_value = (
    SELECT COUNT(*) + 1 FROM users WHERE role = 'TEACHER'
)
WHERE code_type = 'TEACHER'
  AND next_value = 1;
