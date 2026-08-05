WITH normalized_years AS (
    SELECT
        id,
        make_date(substring(code, 1, 4)::integer, 9, 1) AS year_start,
        make_date(substring(code, 1, 4)::integer + 1, 6, 30) AS year_end
    FROM academic_years
    WHERE code ~ '^[0-9]{4}-[0-9]{4}$'
)
UPDATE academic_years ay
SET
    start_date = normalized.year_start,
    end_date = normalized.year_end,
    status = CASE
        WHEN ay.code = '2027-2028' THEN 'ACTIVE'
        WHEN ay.status = 'ACTIVE' THEN 'CLOSED'
        ELSE ay.status
    END
FROM normalized_years normalized
WHERE normalized.id = ay.id;

UPDATE semesters semester
SET
    name = CASE semester.code
        WHEN 'HK1' THEN 'Học kỳ 1'
        WHEN 'HK2' THEN 'Học kỳ 2'
        ELSE semester.name
    END,
    sequence = CASE semester.code
        WHEN 'HK1' THEN 1
        WHEN 'HK2' THEN 2
        ELSE semester.sequence
    END,
    start_date = CASE semester.code
        WHEN 'HK1' THEN make_date(substring(year.code, 1, 4)::integer, 9, 1)
        WHEN 'HK2' THEN make_date(substring(year.code, 1, 4)::integer + 1, 2, 1)
        ELSE semester.start_date
    END,
    end_date = CASE semester.code
        WHEN 'HK1' THEN make_date(substring(year.code, 1, 4)::integer + 1, 1, 31)
        WHEN 'HK2' THEN make_date(substring(year.code, 1, 4)::integer + 1, 6, 30)
        ELSE semester.end_date
    END,
    status = CASE year.status
        WHEN 'ACTIVE' THEN 'ACTIVE'
        WHEN 'CLOSED' THEN 'CLOSED'
        ELSE 'PLANNED'
    END
FROM academic_years year
WHERE year.id = semester.academic_year_id
  AND semester.code IN ('HK1', 'HK2');

CREATE UNIQUE INDEX IF NOT EXISTS uq_academic_year_single_active
    ON academic_years ((1))
    WHERE status = 'ACTIVE';
