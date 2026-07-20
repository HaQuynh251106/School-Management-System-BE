ALTER TABLE announcements ADD COLUMN category varchar(255);
ALTER TABLE announcements ADD COLUMN priority varchar(255);
ALTER TABLE announcements ADD COLUMN status varchar(255);
ALTER TABLE announcements ADD COLUMN recipient_count integer DEFAULT 0 NOT NULL;

ALTER TABLE notifications ADD COLUMN priority varchar(255);

UPDATE announcements SET category = 'GENERAL' WHERE category IS NULL;
UPDATE announcements SET priority = 'NORMAL' WHERE priority IS NULL;
UPDATE announcements SET status = 'SENT' WHERE status IS NULL;
UPDATE notifications SET priority = 'NORMAL' WHERE priority IS NULL;

CREATE INDEX idx_announcement_created_at ON announcements (created_at);
CREATE INDEX idx_announcement_audience ON announcements (audience);
