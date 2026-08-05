BEGIN;
SELECT pg_advisory_xact_lock(hashtext('sse-canonical-scenario-seed'));

-- Per class: #28 has no grades, #29 misses MID, #30 misses FINAL.
DELETE FROM grades g
USING users u
WHERE g.student_id = u.id
  AND u.id LIKE 'g0-student-%'
  AND (
      u.username LIKE '%.028'
      OR (u.username LIKE '%.029' AND g.category = 'MID')
      OR (u.username LIKE '%.030' AND g.category = 'FINAL')
  );

-- Stable lifecycle examples for the upcoming Identity phase.
UPDATE users
SET status = 'PENDING'
WHERE id = (
    SELECT id FROM users
    WHERE id LIKE 'g0-student-%' AND username LIKE '%.027'
    ORDER BY id LIMIT 1
);

UPDATE users
SET status = 'LOCKED'
WHERE id = (
    SELECT id FROM users
    WHERE id LIKE 'g0-student-%' AND username LIKE '%.026'
    ORDER BY id LIMIT 1
);

UPDATE classes c
SET student_count = x.actual_count
FROM (
    SELECT c2.id, count(u.id)::integer AS actual_count
    FROM classes c2
    LEFT JOIN users u
      ON u.class_id = c2.id AND u.role = 'STUDENT' AND u.status <> 'DELETED'
    GROUP BY c2.id
) x
WHERE x.id = c.id AND c.student_count <> x.actual_count;

COMMIT;
