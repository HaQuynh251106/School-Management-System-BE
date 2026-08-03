CREATE TABLE student_class_transfers (
    effective_date date NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    rolled_back_at timestamp(6) with time zone,
    id varchar(255) NOT NULL,
    academic_year_id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    student_name varchar(255),
    source_class_id varchar(255) NOT NULL,
    source_class_code varchar(255) NOT NULL,
    target_class_id varchar(255) NOT NULL,
    target_class_code varchar(255) NOT NULL,
    status varchar(32) NOT NULL,
    created_by varchar(255) NOT NULL,
    created_by_name varchar(255),
    rolled_back_by varchar(255),
    reason varchar(1000) NOT NULL,
    rollback_reason varchar(1000),
    PRIMARY KEY (id)
);

CREATE INDEX idx_class_transfer_year_created
    ON student_class_transfers (academic_year_id, created_at DESC);
CREATE INDEX idx_class_transfer_student_created
    ON student_class_transfers (student_id, created_at DESC);
CREATE INDEX idx_class_transfer_status
    ON student_class_transfers (status);
