CREATE UNIQUE INDEX uq_semester_year_code
    ON semesters (academic_year_id, code);

CREATE UNIQUE INDEX uq_semester_year_sequence
    ON semesters (academic_year_id, sequence);

CREATE UNIQUE INDEX uq_school_holiday_date
    ON school_holidays (date);

CREATE INDEX idx_semester_year_status
    ON semesters (academic_year_id, status);

CREATE INDEX idx_academic_year_status
    ON academic_years (status);
