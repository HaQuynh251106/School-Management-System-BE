create table if not exists teaching_assignment_plans (
    id varchar(255) primary key,
    semester_id varchar(255) not null,
    name varchar(255) not null,
    status varchar(32) not null,
    version_no integer not null,
    assignment_count integer not null default 0,
    warning_summary varchar(2000),
    source_plan_id varchar(255),
    created_by varchar(255),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    published_by varchar(255),
    published_at timestamp with time zone
);

create unique index if not exists uq_teaching_assignment_plan_version
    on teaching_assignment_plans(semester_id, version_no);
create index if not exists idx_teaching_assignment_plan_scope
    on teaching_assignment_plans(semester_id, created_at desc);

create table if not exists teaching_assignment_plan_items (
    id varchar(255) primary key,
    plan_id varchar(255) not null references teaching_assignment_plans(id) on delete cascade,
    class_id varchar(255) not null,
    class_code varchar(255) not null,
    subject_id varchar(255) not null,
    subject_name varchar(255) not null,
    teacher_id varchar(255) not null,
    teacher_name varchar(255) not null,
    weekly_periods integer not null
);

create index if not exists idx_teaching_assignment_plan_item_plan
    on teaching_assignment_plan_items(plan_id);

-- Preserve the current live assignment set as the first published version for
-- every semester that already has data. This migration never rewrites live data.
insert into teaching_assignment_plans(
    id, semester_id, name, status, version_no, assignment_count,
    created_by, created_at, updated_at, published_by, published_at)
select 'tap-migrated-' || ta.semester_id, ta.semester_id, 'Phân công hiện hành trước nâng cấp',
       'PUBLISHED', 1, count(*), 'system', now(), now(), 'system', now()
from teaching_assignments ta
where not exists (
    select 1
    from teaching_assignment_plans p
    where p.semester_id = ta.semester_id and p.version_no = 1
)
group by ta.semester_id;

insert into teaching_assignment_plan_items(
    id, plan_id, class_id, class_code, subject_id, subject_name,
    teacher_id, teacher_name, weekly_periods)
select 'tapi-migrated-' || ta.id, 'tap-migrated-' || ta.semester_id,
       ta.class_id, ta.class_code, ta.subject_id, ta.subject_name,
       ta.teacher_id, ta.teacher_name, ta.weekly_periods
from teaching_assignments ta
where exists (select 1 from teaching_assignment_plans p
              where p.id='tap-migrated-' || ta.semester_id)
  and not exists (select 1 from teaching_assignment_plan_items i
                  where i.id='tapi-migrated-' || ta.id);
