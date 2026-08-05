create table if not exists teacher_schedule_restriction_requests (
    id varchar(64) primary key,
    teacher_id varchar(255) not null,
    teacher_name varchar(255) not null,
    semester_id varchar(255) not null,
    restricted_slots varchar(2000) not null,
    effective_from date not null,
    effective_to date not null,
    reason varchar(1000) not null,
    evidence_url varchar(1000),
    status varchar(32) not null,
    decision_note varchar(1000),
    reviewed_by varchar(255),
    reviewed_at timestamp with time zone,
    revoked_by varchar(255),
    revoked_at timestamp with time zone,
    revoke_reason varchar(1000),
    submitted_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_schedule_restriction_dates check (effective_from <= effective_to),
    constraint ck_schedule_restriction_status check (
        status in ('PENDING','APPROVED','REJECTED','NEEDS_INFO','WITHDRAWN','REVOKED'))
);

create index if not exists idx_schedule_restriction_teacher_semester
    on teacher_schedule_restriction_requests (teacher_id, semester_id, created_at desc);
create index if not exists idx_schedule_restriction_semester_status
    on teacher_schedule_restriction_requests (semester_id, status, created_at desc);

create table if not exists teacher_schedule_restriction_history (
    id varchar(64) primary key,
    request_id varchar(64) not null,
    semester_id varchar(255) not null,
    teacher_id varchar(255) not null,
    action varchar(64) not null,
    previous_status varchar(32),
    new_status varchar(32) not null,
    details varchar(2000),
    actor_id varchar(255) not null,
    created_at timestamp with time zone not null
);

create index if not exists idx_schedule_restriction_history_request
    on teacher_schedule_restriction_history (request_id, created_at desc);
create index if not exists idx_schedule_restriction_history_semester
    on teacher_schedule_restriction_history (semester_id, created_at desc);

-- Preserve approved constraints from the former teacher load workflow. They become
-- auditable Academic Staff approvals instead of editable load-registration fields.
insert into teacher_schedule_restriction_requests (
    id, teacher_id, teacher_name, semester_id, restricted_slots,
    effective_from, effective_to, reason, status, decision_note,
    reviewed_by, reviewed_at, submitted_at, created_at, updated_at)
select 'tsr-migration-' || tlr.id, tlr.teacher_id, tlr.teacher_name, tlr.semester_id,
       tlr.unavailable_slots, sem.start_date, sem.end_date,
       coalesce(nullif(trim(tlr.note), ''), 'Ngoại lệ được chuyển đổi từ dữ liệu đã phê duyệt trước đây'),
       'APPROVED', coalesce(nullif(trim(tlr.review_note), ''), 'Hệ thống chuyển đổi an toàn'),
       coalesce(tlr.reviewed_by, 'SYSTEM_MIGRATION'), coalesce(tlr.reviewed_at, current_timestamp),
       coalesce(tlr.submitted_at, tlr.created_at, current_timestamp),
       coalesce(tlr.created_at, current_timestamp), current_timestamp
from teacher_load_registrations tlr
join semesters sem on sem.id = tlr.semester_id
where tlr.status in ('APPROVED','LOCKED')
  and tlr.unavailable_slots is not null
  and trim(tlr.unavailable_slots) <> ''
  and not exists (
      select 1 from teacher_schedule_restriction_requests existing
      where existing.id = 'tsr-migration-' || tlr.id
  );

insert into teacher_schedule_restriction_history (
    id, request_id, semester_id, teacher_id, action,
    previous_status, new_status, details, actor_id, created_at)
select 'tsh-migration-' || tlr.id, 'tsr-migration-' || tlr.id, tlr.semester_id,
       tlr.teacher_id, 'MIGRATED', tlr.status, 'APPROVED',
       'Chuyển đổi ngoại lệ lịch từ quy trình đăng ký tải dạy cũ',
       'SYSTEM_MIGRATION', current_timestamp
from teacher_load_registrations tlr
where exists (
    select 1 from teacher_schedule_restriction_requests request
    where request.id = 'tsr-migration-' || tlr.id
)
and not exists (
    select 1 from teacher_schedule_restriction_history history
    where history.id = 'tsh-migration-' || tlr.id
);

-- Load records are now immutable system snapshots. Old preference fields must no
-- longer influence assignment or timetable generation.
update teacher_load_registrations
set unavailable_slots = null,
    preferred_grade_levels = null,
    preferred_days_off = null,
    note = null,
    review_note = null,
    submitted_at = null,
    reviewed_at = null,
    reviewed_by = null,
    extended_closes_on = null,
    status = 'SYSTEM',
    updated_at = current_timestamp;
