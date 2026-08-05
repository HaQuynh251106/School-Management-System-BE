alter table teacher_load_registrations add column if not exists standard_weekly_periods integer;
alter table teacher_load_registrations add column if not exists min_weekly_periods integer;
alter table teacher_load_registrations add column if not exists max_daily_periods integer;
alter table teacher_load_registrations add column if not exists max_consecutive_periods integer;
alter table teacher_load_registrations add column if not exists preferred_days_off varchar(250);
alter table teacher_load_registrations add column if not exists extended_closes_on date;

update teacher_load_registrations
set standard_weekly_periods = coalesce(standard_weekly_periods, least(max_weekly_periods, 20)),
    min_weekly_periods = coalesce(min_weekly_periods, least(max_weekly_periods, 18)),
    max_daily_periods = coalesce(max_daily_periods, 6),
    max_consecutive_periods = coalesce(max_consecutive_periods, 4);

-- Keep the new columns nullable at database level for backward-compatible imports and
-- older operational scripts. The application always normalizes them before saving,
-- while legacy rows are interpreted with the same defaults in the service layer.

alter table teacher_load_registrations add constraint ck_teacher_standard_weekly_periods
    check (standard_weekly_periods between 1 and 60);
alter table teacher_load_registrations add constraint ck_teacher_min_weekly_periods
    check (min_weekly_periods between 1 and 60);
alter table teacher_load_registrations add constraint ck_teacher_max_daily_periods
    check (max_daily_periods between 1 and 12);
alter table teacher_load_registrations add constraint ck_teacher_max_consecutive_periods
    check (max_consecutive_periods between 1 and 6);
alter table teacher_load_registrations add constraint ck_teacher_load_period_order
    check (min_weekly_periods <= standard_weekly_periods and standard_weekly_periods <= max_weekly_periods);

create table if not exists teacher_load_registration_windows (
    id varchar(64) primary key,
    semester_id varchar(64) not null unique,
    opens_on date not null,
    closes_on date not null,
    status varchar(32) not null,
    configured_by varchar(64),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_teacher_load_window_dates check (opens_on <= closes_on),
    constraint ck_teacher_load_window_status check (status in ('OPEN', 'CLOSED'))
);

create table if not exists teacher_load_registration_history (
    id varchar(64) primary key,
    registration_id varchar(64) not null,
    semester_id varchar(64) not null,
    teacher_id varchar(64) not null,
    action varchar(64) not null,
    previous_status varchar(32),
    new_status varchar(32),
    details varchar(2000),
    actor_id varchar(64),
    created_at timestamp with time zone not null
);

create index if not exists idx_teacher_load_history_registration
    on teacher_load_registration_history (registration_id, created_at desc);
create index if not exists idx_teacher_load_history_semester
    on teacher_load_registration_history (semester_id, created_at desc);
