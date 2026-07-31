CREATE TABLE IF NOT EXISTS class_placement_runs (
    id VARCHAR(64) PRIMARY KEY,
    academic_year_id VARCHAR(255) NOT NULL,
    grade_level VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    assigned_count INTEGER NOT NULL DEFAULT 0,
    created_class_ids TEXT,
    configuration_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    rolled_back_at TIMESTAMP WITH TIME ZONE,
    rolled_back_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_class_placement_run_scope
    ON class_placement_runs (academic_year_id, grade_level, created_at DESC);

CREATE TABLE IF NOT EXISTS class_placement_items (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    previous_class_id VARCHAR(255),
    assigned_class_id VARCHAR(255) NOT NULL,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_class_placement_item_run
        FOREIGN KEY (run_id) REFERENCES class_placement_runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_class_placement_item_run
    ON class_placement_items (run_id);
CREATE INDEX IF NOT EXISTS idx_class_placement_item_student
    ON class_placement_items (student_id);
