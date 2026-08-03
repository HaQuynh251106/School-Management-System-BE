CREATE TABLE IF NOT EXISTS curriculum_requirement_history (
    id VARCHAR(255) PRIMARY KEY,
    semester_id VARCHAR(255) NOT NULL,
    grade_level VARCHAR(50) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    subject_name VARCHAR(255) NOT NULL,
    action VARCHAR(30) NOT NULL,
    previous_weekly_periods INTEGER,
    new_weekly_periods INTEGER,
    actor_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_curriculum_history_semester_created
    ON curriculum_requirement_history (semester_id, created_at DESC);
