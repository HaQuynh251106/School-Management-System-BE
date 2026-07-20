ALTER TABLE announcements ADD COLUMN holiday_start_date date;
ALTER TABLE announcements ADD COLUMN holiday_end_date date;

CREATE INDEX idx_announcement_holiday_dates
    ON announcements (category, audience, holiday_start_date, holiday_end_date);
