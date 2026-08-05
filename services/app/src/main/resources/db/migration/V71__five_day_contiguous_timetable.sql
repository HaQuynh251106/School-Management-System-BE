-- Chuẩn vận hành mới: Thứ 2-Thứ 6, mỗi ngày 5 tiết liền mạch.
-- Chỉ điều chỉnh năm học đang vận hành/sắp diễn ra; dữ liệu lịch sử giữ nguyên.
update curriculum_requirements cr
set weekly_periods = case (
    select upper(s.code) from subjects s where s.id = cr.subject_id
)
    when 'MATH' then 4
    when 'LIT' then 4
    when 'ENG' then 3
    when 'PHYS' then 2
    when 'CHEM' then 2
    when 'BIO' then 2
    when 'HIST' then 1
    when 'GEO' then 1
    when 'IT' then 2
    when 'TECH' then 1
    when 'PE' then 2
    when 'CIVIC' then 1
    else cr.weekly_periods
end
where cr.semester_id in (
    select sem.id from semesters sem where sem.status in ('ACTIVE', 'PLANNED')
);

update teaching_assignments ta
set weekly_periods = case (
    select upper(s.code) from subjects s where s.id = ta.subject_id
)
    when 'MATH' then 4
    when 'LIT' then 4
    when 'ENG' then 3
    when 'PHYS' then 2
    when 'CHEM' then 2
    when 'BIO' then 2
    when 'HIST' then 1
    when 'GEO' then 1
    when 'IT' then 2
    when 'TECH' then 1
    when 'PE' then 2
    when 'CIVIC' then 1
    else ta.weekly_periods
end
where ta.semester_id in (
    select sem.id from semesters sem where sem.status in ('ACTIVE', 'PLANNED')
);

update teacher_load_registrations
set max_daily_periods = least(coalesce(max_daily_periods, 5), 5),
    max_consecutive_periods = least(coalesce(max_consecutive_periods, 4), 5),
    unavailable_slots = case
        when unavailable_slots like '%SAT%' or unavailable_slots like '%:6%' then null
        else unavailable_slots
    end,
    preferred_days_off = case
        when preferred_days_off like '%SAT%' then null
        else preferred_days_off
    end;

alter table teacher_load_registrations drop constraint if exists ck_teacher_max_daily_periods;
alter table teacher_load_registrations drop constraint if exists ck_teacher_max_consecutive_periods;
alter table teacher_load_registrations add constraint ck_teacher_max_daily_periods
    check (max_daily_periods between 1 and 5);
alter table teacher_load_registrations add constraint ck_teacher_max_consecutive_periods
    check (max_consecutive_periods between 1 and 5);
