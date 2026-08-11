alter table fee_periods add column if not exists scope_type varchar(24);
alter table fee_periods add column if not exists scope_grade_level varchar(32);
alter table fee_periods add column if not exists scope_class_id varchar(64);

update fee_periods
set scope_type = case
    when apply_to_grades is not null and trim(apply_to_grades) <> '' then 'GRADE'
    else 'SCHOOL'
end
where scope_type is null;

create table if not exists fee_period_recipients (
    id varchar(64) primary key,
    fee_period_id varchar(64) not null,
    student_id varchar(64) not null,
    constraint uk_fee_period_recipient unique (fee_period_id, student_id)
);

create index if not exists idx_fee_period_recipient_period
    on fee_period_recipients (fee_period_id);

create table if not exists fee_period_adjustments (
    id varchar(64) primary key,
    fee_period_id varchar(64) not null,
    student_id varchar(64) not null,
    type varchar(24) not null,
    amount bigint not null default 0,
    reason varchar(500),
    constraint uk_fee_period_adjustment unique (fee_period_id, student_id)
);

create index if not exists idx_fee_period_adjustment_period
    on fee_period_adjustments (fee_period_id);
