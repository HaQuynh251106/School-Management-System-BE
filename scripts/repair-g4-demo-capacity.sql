-- Chuan hoa nhip Ngu van trong ban dieu chinh GĐ3 va can lai phan cong K10.
-- Tong tiet mon hoc khong doi; 45 tiet/hoc ky tuong duong 3 tiet/tuan.
with latest_draft as (
    select id
    from academic_training_plans
    where academic_year_id = 'ay-a795cde3c4'
      and grade_level = 'K10'
      and status in ('DRAFT', 'REVISION_REQUIRED')
    order by version_number desc
    limit 1
)
update academic_training_plan_subjects plan_subject
set weekly_periods = 3,
    updated_at = now()
from latest_draft
where plan_subject.plan_id = latest_draft.id
  and plan_subject.subject_id = 'sj-lit';

with latest_draft as (
    select id
    from academic_training_plans
    where academic_year_id = 'ay-a795cde3c4'
      and grade_level = 'K10'
      and status in ('DRAFT', 'REVISION_REQUIRED')
    order by version_number desc
    limit 1
)
update academic_training_plan_subjects plan_subject
set total_periods = 36,
    updated_at = now()
from latest_draft
where plan_subject.plan_id = latest_draft.id
  and plan_subject.semester_id = 'sm-2027-1'
  and plan_subject.subject_id = 'sj-bio';

-- Dong bo 1 tiet Sinh hoc HK1 con thieu trong giai doan, bai hoc va phan phoi tuan.
with target_subject as (
    select plan_subject.id
    from academic_training_plan_subjects plan_subject
    join academic_training_plans plan on plan.id = plan_subject.plan_id
    where plan.academic_year_id = 'ay-a795cde3c4'
      and plan.grade_level = 'K10'
      and plan.status in ('DRAFT', 'REVISION_REQUIRED')
      and plan_subject.semester_id = 'sm-2027-1'
      and plan_subject.subject_id = 'sj-bio'
    order by plan.version_number desc
    limit 1
)
update academic_training_plan_stages stage
set target_periods = target_periods + 1,
    updated_at = now()
from target_subject
where stage.plan_subject_id = target_subject.id
  and stage.code = 'SEMESTER'
  and (select sum(target_periods)
       from academic_training_plan_stages current_stage
       where current_stage.plan_subject_id = target_subject.id) < 36;

with target_subject as (
    select plan_subject.id
    from academic_training_plan_subjects plan_subject
    join academic_training_plans plan on plan.id = plan_subject.plan_id
    where plan.academic_year_id = 'ay-a795cde3c4'
      and plan.grade_level = 'K10'
      and plan.status in ('DRAFT', 'REVISION_REQUIRED')
      and plan_subject.semester_id = 'sm-2027-1'
      and plan_subject.subject_id = 'sj-bio'
    order by plan.version_number desc
    limit 1
)
update academic_curriculum_items item
set planned_periods = planned_periods + 1,
    updated_at = now()
from target_subject
where item.plan_subject_id = target_subject.id
  and item.item_type = 'LESSON'
  and (select sum(planned_periods)
       from academic_curriculum_items lesson
       where lesson.plan_subject_id = target_subject.id
         and lesson.item_type = 'LESSON') < 36;

with target_subject as (
    select plan_subject.id
    from academic_training_plan_subjects plan_subject
    join academic_training_plans plan on plan.id = plan_subject.plan_id
    where plan.academic_year_id = 'ay-a795cde3c4'
      and plan.grade_level = 'K10'
      and plan.status in ('DRAFT', 'REVISION_REQUIRED')
      and plan_subject.semester_id = 'sm-2027-1'
      and plan_subject.subject_id = 'sj-bio'
    order by plan.version_number desc
    limit 1
), target_distribution as (
    select distribution.id
    from academic_curriculum_distributions distribution
    join target_subject on target_subject.id = distribution.plan_subject_id
    order by distribution.week_number desc, distribution.id
    limit 1
)
update academic_curriculum_distributions distribution
set periods = periods + 1,
    updated_at = now()
from target_distribution
where distribution.id = target_distribution.id
  and (select sum(periods)
       from academic_curriculum_distributions current_distribution
       join target_subject on target_subject.id = current_distribution.plan_subject_id) < 36;

-- Ngu van la mon duoc chon cho khoi 3 tiet; moi hoc ky can mot tuan du phong.
with target_plan as (
    select id
    from academic_training_plans
    where academic_year_id = 'ay-a795cde3c4'
      and grade_level = 'K10'
      and status in ('DRAFT', 'REVISION_REQUIRED', 'APPROVED')
    order by version_number desc
    limit 1
)
insert into academic_training_plan_special_weeks (
    id, plan_subject_id, week_type, week_number, name, description, created_at, updated_at
)
select 'buffer-g4-' || substr(md5(plan_subject.id), 1, 16),
       plan_subject.id, 'BUFFER', 18, 'Tuan du phong',
       'Du phong dieu chinh tien do va lich day bu GĐ4', now(), now()
from academic_training_plan_subjects plan_subject
join target_plan on target_plan.id = plan_subject.plan_id
where plan_subject.subject_id = 'sj-lit'
  and not exists (
      select 1
      from academic_training_plan_special_weeks existing
      where existing.plan_subject_id = plan_subject.id
        and existing.week_type = 'BUFFER'
  )
on conflict (plan_subject_id, week_number) do nothing;

with target_plan as (
    select id
    from academic_training_plans
    where academic_year_id = 'ay-a795cde3c4'
      and grade_level = 'K10'
      and status in ('DRAFT', 'REVISION_REQUIRED')
    order by version_number desc
    limit 1
), source_plan as (
    select id
    from academic_training_plans
    where academic_year_id = 'ay-a795cde3c4'
      and grade_level = 'K10'
      and status = 'PUBLISHED'
    order by version_number desc
    limit 1
)
insert into academic_assessment_plans (
    id, plan_id, semester_id, class_id, subject_id, assessment_type,
    week_number, duration_minutes, teacher_id, notes, created_at, updated_at,
    name, assessment_form, curriculum_item_ids, result_method
)
select 'assessment-g4-' || substr(md5(source.id || target.id), 1, 16),
       target.id, source.semester_id, source.class_id, source.subject_id,
       source.assessment_type, source.week_number, source.duration_minutes,
       source.teacher_id, source.notes, now(), now(), source.name,
       source.assessment_form, null, source.result_method
from academic_assessment_plans source
cross join target_plan target
cross join source_plan published
where source.plan_id = published.id
  and not exists (
      select 1
      from academic_assessment_plans existing
      where existing.plan_id = target.id
        and existing.semester_id = source.semester_id
        and existing.class_id is not distinct from source.class_id
        and existing.subject_id = source.subject_id
        and existing.assessment_type = source.assessment_type
        and existing.week_number = source.week_number
  );

with teacher_map(class_code, teacher_id, teacher_name) as (
    values
        ('10A1',  'u-t-lit',          'Tran Van Huy'),
        ('10A2',  'u-t-lit',          'Tran Van Huy'),
        ('10A3',  'g0-teacher-lit-4', 'Tran Thu Ha'),
        ('10A4',  'g0-teacher-lit-4', 'Tran Thu Ha'),
        ('10A5',  'g0-teacher-lit-5', 'Nguyen Ngoc Lan'),
        ('10A6',  'g0-teacher-lit-5', 'Nguyen Ngoc Lan'),
        ('10A7',  'g0-teacher-lit-6', 'Do Mai Anh'),
        ('10A8',  'g0-teacher-lit-7', 'Le Thu Huyen'),
        ('10A9',  'g0-teacher-lit-8', 'Pham Ngoc Anh'),
        ('10A10', 'g0-teacher-lit-9', 'Nguyen Thanh Ha')
)
update teacher_class_subjects assignment
set teacher_id = teacher_map.teacher_id,
    teacher_name = teacher_map.teacher_name,
    updated_at = now()
from classes school_class, teacher_map
where assignment.class_id = school_class.id
  and school_class.academic_year_id = 'ay-a795cde3c4'
  and school_class.code = teacher_map.class_code
  and assignment.subject_id = 'sj-lit'
  and assignment.status = 'ACTIVE';
