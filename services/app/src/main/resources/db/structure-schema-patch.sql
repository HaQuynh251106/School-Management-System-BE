-- Development schema patch. Class codes repeat across school years, but not within one year.
ALTER TABLE IF EXISTS classes DROP CONSTRAINT IF EXISTS uk_ivcaxrbwnp0dosg2gj4i3sxpq;
CREATE UNIQUE INDEX IF NOT EXISTS uk_classes_academic_year_code
    ON classes (academic_year_id, code);
