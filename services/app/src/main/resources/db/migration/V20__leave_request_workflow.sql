CREATE TABLE leave_requests (
    start_date date NOT NULL,
    end_date date NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    parent_confirmed_at timestamp(6) with time zone,
    decided_at timestamp(6) with time zone,
    id varchar(255) NOT NULL,
    student_id varchar(255) NOT NULL,
    student_name varchar(255),
    class_id varchar(255) NOT NULL,
    class_code varchar(255),
    status varchar(255) NOT NULL,
    parent_id varchar(255),
    parent_name varchar(255),
    homeroom_teacher_id varchar(255),
    homeroom_teacher_name varchar(255),
    reason varchar(2000) NOT NULL,
    decision_note varchar(1000),
    PRIMARY KEY (id)
);

CREATE INDEX idx_leave_student ON leave_requests (student_id, created_at);
CREATE INDEX idx_leave_class_status ON leave_requests (class_id, status);
