CREATE INDEX IF NOT EXISTS idx_notifications_recipient_read_created
    ON notifications(recipient_id, read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_module_action_created
    ON audit_logs(module, action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_sender_recipient_created
    ON chat_messages(sender_id, recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_recipient_sender_created
    ON chat_messages(recipient_id, sender_id, created_at DESC);
