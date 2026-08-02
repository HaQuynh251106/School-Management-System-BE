create table if not exists student_interventions (
    id varchar(80) primary key,
    student_id varchar(80) not null,
    class_id varchar(80) not null,
    teacher_id varchar(80) not null,
    category varchar(40) not null,
    severity varchar(20) not null,
    title varchar(300) not null,
    description varchar(3000) not null,
    action_taken varchar(3000),
    follow_up_date date,
    status varchar(30) not null,
    parent_contacted boolean not null default false,
    parent_contacted_at timestamp,
    resolved_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    version bigint not null default 0
);

create index if not exists idx_intervention_class_status
    on student_interventions (class_id, status, updated_at);
create index if not exists idx_intervention_student_history
    on student_interventions (student_id, created_at);
create index if not exists idx_intervention_teacher
    on student_interventions (teacher_id, updated_at);
