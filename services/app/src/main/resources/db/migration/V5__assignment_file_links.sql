ALTER TABLE assignments ADD COLUMN attachment_file_id varchar(255);
ALTER TABLE assignment_submissions ADD COLUMN attachment_file_id varchar(255);

CREATE INDEX idx_asg_attachment_file ON assignments (attachment_file_id);
CREATE INDEX idx_sub_attachment_file ON assignment_submissions (attachment_file_id);
