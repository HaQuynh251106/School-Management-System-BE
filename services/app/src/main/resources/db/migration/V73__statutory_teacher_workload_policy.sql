create table if not exists teacher_workload_policies (
    id varchar(64) primary key,
    academic_year_id varchar(255) not null unique,
    school_level varchar(32) not null default 'THPT',
    base_weekly_periods integer not null default 17,
    teaching_weeks integer not null default 35,
    max_overtime_percent integer not null default 50,
    homeroom_reduction_periods integer not null default 4,
    effective_from date,
    effective_to date,
    source_document varchar(500),
    active boolean not null default true,
    configured_by varchar(255),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_workload_policy_base check (base_weekly_periods between 1 and 60),
    constraint ck_workload_policy_weeks check (teaching_weeks between 1 and 52),
    constraint ck_workload_policy_overtime check (max_overtime_percent between 0 and 50),
    constraint ck_workload_policy_homeroom check (homeroom_reduction_periods between 0 and 17)
);

create table if not exists teacher_workload_adjustments (
    id varchar(64) primary key,
    teacher_id varchar(255) not null,
    academic_year_id varchar(255) not null,
    category varchar(32) not null,
    duty_type varchar(64) not null,
    title varchar(255) not null,
    weekly_periods integer not null,
    effective_from date,
    effective_to date,
    reason varchar(1000),
    status varchar(32) not null,
    approved_by varchar(255),
    approved_at timestamp with time zone,
    revoked_by varchar(255),
    revoked_at timestamp with time zone,
    revoke_reason varchar(1000),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_workload_adjustment_category check (category in ('REDUCTION','CONVERSION','OVERTIME')),
    constraint ck_workload_adjustment_status check (status in ('APPROVED','REJECTED','REVOKED')),
    constraint ck_workload_adjustment_periods check (weekly_periods between 1 and 17)
);
create index if not exists idx_workload_adjustment_teacher_year
    on teacher_workload_adjustments (teacher_id, academic_year_id, status);

alter table teacher_load_registrations add column if not exists base_weekly_periods integer;
alter table teacher_load_registrations add column if not exists reduction_weekly_periods integer;
alter table teacher_load_registrations add column if not exists converted_weekly_periods integer;
alter table teacher_load_registrations add column if not exists approved_overtime_weekly_periods integer;
alter table teacher_load_registrations add column if not exists annual_target_periods integer;

insert into teacher_workload_policies (
    id,academic_year_id,school_level,base_weekly_periods,teaching_weeks,max_overtime_percent,
    homeroom_reduction_periods,effective_from,effective_to,source_document,active,configured_by,created_at,updated_at)
select 'twp-' || ay.id,ay.id,'THPT',17,35,50,4,ay.start_date,ay.end_date,
       'Thông tư 05/2025/TT-BGDĐT','true','SYSTEM',current_timestamp,current_timestamp
from academic_years ay
;

update teacher_load_registrations tlr
set base_weekly_periods=17,
    reduction_weekly_periods=case when exists (
        select 1 from semesters sem join classes c on c.academic_year_id=sem.academic_year_id
        where sem.id=tlr.semester_id and c.homeroom_teacher_id=tlr.teacher_id
    ) then 4 else 0 end,
    converted_weekly_periods=0,
    approved_overtime_weekly_periods=0,
    annual_target_periods=(17-case when exists (
        select 1 from semesters sem join classes c on c.academic_year_id=sem.academic_year_id
        where sem.id=tlr.semester_id and c.homeroom_teacher_id=tlr.teacher_id
    ) then 4 else 0 end)*35;

-- Preserve an auditable bridge for legacy assignments that were valid under the
-- old user-entered limits.  The migration never grants more than the statutory
-- 50% weekly overtime cap; workloads above that cap remain visible as blocking
-- violations and must be redistributed by Academic Staff.
insert into teacher_workload_adjustments (
    id,teacher_id,academic_year_id,category,duty_type,title,weekly_periods,
    reason,status,approved_by,approved_at,created_at,updated_at)
select 'twa-migration-' || legacy.teacher_id || '-' || legacy.academic_year_id,
       legacy.teacher_id,legacy.academic_year_id,'OVERTIME','LEGACY_ASSIGNMENT',
       'Chuyển đổi tải dạy từ dữ liệu trước Thông tư 05/2025/TT-BGDĐT',
       least(8,legacy.assigned_periods-legacy.target_periods),
       'Hệ thống tạo tự động khi chuyển đổi; Giáo vụ cần rà soát và thu hồi khi đã cân bằng lại phân công.',
       'APPROVED','SYSTEM_MIGRATION',current_timestamp,current_timestamp,current_timestamp
from (
    select workload.teacher_id,workload.academic_year_id,max(workload.assigned_periods) assigned_periods,
           17-case when exists (
               select 1 from classes c
               where c.academic_year_id=workload.academic_year_id
                 and c.homeroom_teacher_id=workload.teacher_id
           ) then 4 else 0 end target_periods
    from (
        select ta.teacher_id,sem.academic_year_id,ta.semester_id,sum(ta.weekly_periods) assigned_periods
        from teaching_assignments ta
        join semesters sem on sem.id=ta.semester_id
        group by ta.teacher_id,sem.academic_year_id,ta.semester_id
    ) workload
    group by workload.teacher_id,workload.academic_year_id
) legacy
where legacy.assigned_periods>legacy.target_periods;

update teacher_load_registrations tlr
set approved_overtime_weekly_periods=coalesce((
    select sum(adj.weekly_periods)
    from teacher_workload_adjustments adj
    join semesters sem on sem.academic_year_id=adj.academic_year_id
    where sem.id=tlr.semester_id and adj.teacher_id=tlr.teacher_id
      and adj.category='OVERTIME' and adj.status='APPROVED'
),0);

update teacher_load_registrations
set standard_weekly_periods=greatest(0,base_weekly_periods-reduction_weekly_periods-converted_weekly_periods),
    min_weekly_periods=greatest(0,base_weekly_periods-reduction_weekly_periods-converted_weekly_periods),
    max_weekly_periods=greatest(0,base_weekly_periods-reduction_weekly_periods-converted_weekly_periods)
        +approved_overtime_weekly_periods,
    max_daily_periods=5,
    max_consecutive_periods=5;

alter table teacher_load_registrations drop constraint if exists ck_teacher_max_weekly_periods;
alter table teacher_load_registrations drop constraint if exists ck_teacher_standard_weekly_periods;
alter table teacher_load_registrations drop constraint if exists ck_teacher_min_weekly_periods;
alter table teacher_load_registrations drop constraint if exists ck_teacher_load_period_order;
alter table teacher_load_registrations add constraint ck_teacher_max_weekly_periods check (max_weekly_periods between 0 and 60);
alter table teacher_load_registrations add constraint ck_teacher_standard_weekly_periods check (standard_weekly_periods between 0 and 60);
alter table teacher_load_registrations add constraint ck_teacher_min_weekly_periods check (min_weekly_periods between 0 and 60);
alter table teacher_load_registrations add constraint ck_teacher_load_period_order
    check (min_weekly_periods <= standard_weekly_periods and standard_weekly_periods <= max_weekly_periods);
