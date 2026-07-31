create table if not exists curriculum_requirements (
    id varchar(64) primary key,
    semester_id varchar(64) not null,
    grade_level varchar(32) not null,
    subject_id varchar(64) not null,
    subject_name varchar(255) not null,
    weekly_periods integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_curriculum_requirement unique (semester_id, grade_level, subject_id),
    constraint ck_curriculum_weekly_periods check (weekly_periods between 1 and 20)
);

create index if not exists idx_curriculum_requirement_semester
    on curriculum_requirements (semester_id);

create table if not exists teacher_load_registrations (
    id varchar(64) primary key,
    teacher_id varchar(64) not null,
    teacher_name varchar(255) not null,
    semester_id varchar(64) not null,
    max_weekly_periods integer not null,
    unavailable_slots varchar(2000),
    preferred_grade_levels varchar(500),
    note varchar(1000),
    review_note varchar(1000),
    status varchar(32) not null,
    submitted_at timestamp with time zone,
    reviewed_at timestamp with time zone,
    reviewed_by varchar(64),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_teacher_load_registration unique (teacher_id, semester_id),
    constraint ck_teacher_max_weekly_periods check (max_weekly_periods between 1 and 60)
);

create index if not exists idx_teacher_load_registration_semester_status
    on teacher_load_registrations (semester_id, status);
