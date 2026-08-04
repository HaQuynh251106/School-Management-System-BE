create table if not exists conduct_rule_sets (
    id varchar(80) primary key,
    academic_year_id varchar(80) not null,
    semester_id varchar(80),
    scope_key varchar(80) not null,
    version_no integer not null,
    status varchar(20) not null,
    attendance_weight float(53) not null,
    discipline_weight float(53) not null,
    responsibility_weight float(53) not null,
    participation_weight float(53) not null,
    good_min float(53) not null,
    fair_min float(53) not null,
    average_min float(53) not null,
    min_attendance_records integer not null default 10,
    min_participation_evidence integer not null default 0,
    created_by varchar(80) not null,
    created_at timestamp not null,
    activated_at timestamp,
    version bigint not null default 0
);

create unique index if not exists uk_conduct_rule_version
    on conduct_rule_sets (academic_year_id, scope_key, version_no);
create index if not exists idx_conduct_active_rule
    on conduct_rule_sets (academic_year_id, scope_key, status);

create table if not exists conduct_evidence (
    id varchar(80) primary key,
    academic_year_id varchar(80) not null,
    semester_id varchar(80),
    student_id varchar(80) not null,
    class_id varchar(80) not null,
    teacher_id varchar(80) not null,
    category varchar(30) not null,
    impact_points float(53) not null,
    title varchar(300) not null,
    description varchar(3000),
    occurred_on date not null,
    source_type varchar(30) not null,
    source_ref varchar(160) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    version bigint not null default 0
);

create unique index if not exists uk_conduct_evidence_source
    on conduct_evidence (academic_year_id, source_type, source_ref);
create index if not exists idx_conduct_evidence_student_scope
    on conduct_evidence (academic_year_id, student_id, occurred_on);
create index if not exists idx_conduct_evidence_class_scope
    on conduct_evidence (academic_year_id, class_id, occurred_on);

create table if not exists conduct_evaluations (
    id varchar(80) primary key,
    academic_year_id varchar(80) not null,
    semester_id varchar(80),
    scope_key varchar(80) not null,
    student_id varchar(80) not null,
    class_id varchar(80) not null,
    rule_set_id varchar(80) not null,
    readiness varchar(30) not null,
    suggested_score float(53),
    suggested_grade varchar(20),
    final_grade varchar(20),
    override_reason varchar(2000),
    workflow_status varchar(30) not null,
    decided_by varchar(80),
    decided_at timestamp,
    calculated_at timestamp not null,
    updated_at timestamp not null,
    version bigint not null default 0
);

create unique index if not exists uk_conduct_evaluation_scope
    on conduct_evaluations (academic_year_id, scope_key, student_id);
create index if not exists idx_conduct_evaluation_class
    on conduct_evaluations (academic_year_id, class_id, workflow_status);

create table if not exists conduct_evaluation_audits (
    id varchar(80) primary key,
    evaluation_id varchar(80) not null,
    action varchar(60) not null,
    previous_grade varchar(20),
    new_grade varchar(20),
    note varchar(2000),
    actor_id varchar(80) not null,
    created_at timestamp not null
);

create index if not exists idx_conduct_audit_evaluation
    on conduct_evaluation_audits (evaluation_id, created_at);
