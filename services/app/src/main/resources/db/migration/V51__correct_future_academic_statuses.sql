UPDATE semesters
SET status = 'PLANNED'
WHERE status = 'ACTIVE' AND start_date > CURRENT_DATE;

UPDATE academic_years
SET status = 'PLANNED'
WHERE status = 'ACTIVE' AND start_date > CURRENT_DATE;

