-- Trung tam cong viec dung chung cho Admin, Giao vu, Ke toan va Giao vien.
-- Migration chi mo rong cau truc lich su, khong xoa du lieu cu.
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS progress_percent INTEGER NOT NULL DEFAULT 0;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS previous_status VARCHAR(32);
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(2000);
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS delay_reason VARCHAR(2000);
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS snoozed_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS auto_managed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS source_key VARCHAR(512);
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS parent_task_id VARCHAR(255);
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS completed_on_time BOOLEAN;
ALTER TABLE operation_tasks ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE operation_tasks DROP CONSTRAINT IF EXISTS chk_operation_task_progress;
ALTER TABLE operation_tasks ADD CONSTRAINT chk_operation_task_progress
    CHECK (progress_percent BETWEEN 0 AND 100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_operation_task_source_key
    ON operation_tasks(source_key);
CREATE INDEX IF NOT EXISTS idx_operation_task_due_active
    ON operation_tasks(due_date, status, snoozed_until);
CREATE INDEX IF NOT EXISTS idx_operation_task_parent ON operation_tasks(parent_task_id);

CREATE TABLE IF NOT EXISTS operation_task_checklist_items (
    id VARCHAR(255) PRIMARY KEY,
    task_id VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0,
    completed_by VARCHAR(255),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_operation_checklist_task FOREIGN KEY (task_id)
        REFERENCES operation_tasks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS operation_task_history (
    id VARCHAR(255) PRIMARY KEY,
    task_id VARCHAR(255) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    actor_name VARCHAR(255),
    action VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    detail VARCHAR(4000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_operation_history_task FOREIGN KEY (task_id)
        REFERENCES operation_tasks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS operation_task_attachments (
    id VARCHAR(255) PRIMARY KEY,
    task_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    file_url VARCHAR(2000) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT,
    uploaded_by VARCHAR(255) NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_operation_attachment_task FOREIGN KEY (task_id)
        REFERENCES operation_tasks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS operation_task_reminders (
    id VARCHAR(255) PRIMARY KEY,
    task_id VARCHAR(255) NOT NULL,
    reminder_type VARCHAR(32) NOT NULL,
    scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2000),
    sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_operation_reminder_task FOREIGN KEY (task_id)
        REFERENCES operation_tasks(id) ON DELETE CASCADE,
    CONSTRAINT uq_operation_reminder UNIQUE(task_id, reminder_type, scheduled_for)
);

CREATE INDEX IF NOT EXISTS idx_operation_checklist_task
    ON operation_task_checklist_items(task_id, position);
CREATE INDEX IF NOT EXISTS idx_operation_history_task
    ON operation_task_history(task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_attachment_task
    ON operation_task_attachments(task_id, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_reminder_queue
    ON operation_task_reminders(status, scheduled_for);
