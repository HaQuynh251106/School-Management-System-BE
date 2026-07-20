ALTER TABLE chat_messages ADD COLUMN attachment_file_id varchar(255);
ALTER TABLE chat_messages ADD COLUMN attachment_name varchar(255);
ALTER TABLE chat_messages ADD COLUMN read_at timestamp(6) with time zone;
CREATE INDEX idx_chat_attachment ON chat_messages (attachment_file_id);
