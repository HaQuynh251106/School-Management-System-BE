-- Every fee period belongs to exactly one academic year. Existing legacy rows are
-- assigned to the active year (or the latest year when no year is active) before
-- the database constraint is enabled.
UPDATE fee_periods period
SET academic_year_id = selected_year.id
FROM (
    SELECT id
    FROM academic_years
    ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 WHEN status = 'PLANNED' THEN 1 ELSE 2 END,
             start_date DESC NULLS LAST,
             code DESC
    LIMIT 1
) selected_year
WHERE period.academic_year_id IS NULL OR BTRIM(period.academic_year_id) = '';

ALTER TABLE fee_periods ALTER COLUMN academic_year_id SET NOT NULL;

ALTER TABLE fee_periods
    ADD CONSTRAINT fk_fee_period_academic_year
    FOREIGN KEY (academic_year_id) REFERENCES academic_years(id);

CREATE INDEX IF NOT EXISTS idx_fee_period_academic_year
    ON fee_periods(academic_year_id, status, created_at DESC);

-- Historical code allowed duplicate invoice generation under concurrent requests.
-- Consolidate legacy duplicates, preserving the oldest invoice as the canonical row.
CREATE TEMP TABLE duplicate_invoice_map AS
SELECT id AS duplicate_id,
       FIRST_VALUE(id) OVER (PARTITION BY fee_period_id, student_id ORDER BY issued_at NULLS LAST, id) AS keeper_id,
       ROW_NUMBER() OVER (PARTITION BY fee_period_id, student_id ORDER BY issued_at NULLS LAST, id) AS row_no
FROM invoices;

DELETE FROM duplicate_invoice_map WHERE row_no = 1;

UPDATE payments payment
SET invoice_id = duplicate.keeper_id
FROM duplicate_invoice_map duplicate
WHERE payment.invoice_id = duplicate.duplicate_id;

DELETE FROM invoice_items
WHERE invoice_id IN (SELECT duplicate_id FROM duplicate_invoice_map);

DELETE FROM invoices
WHERE id IN (SELECT duplicate_id FROM duplicate_invoice_map);

CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_fee_period_student
    ON invoices(fee_period_id, student_id);

DROP TABLE duplicate_invoice_map;
