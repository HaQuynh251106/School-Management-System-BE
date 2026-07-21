ALTER TABLE exam_periods ADD COLUMN schedule_published boolean NOT NULL DEFAULT false;
ALTER TABLE exam_periods ADD COLUMN schedule_revision integer NOT NULL DEFAULT 0;
ALTER TABLE exam_periods ADD COLUMN schedule_published_at timestamp with time zone;
ALTER TABLE exam_periods ADD COLUMN schedule_published_by varchar(255);

CREATE INDEX idx_exam_period_schedule_published
    ON exam_periods(schedule_published, start_date);
