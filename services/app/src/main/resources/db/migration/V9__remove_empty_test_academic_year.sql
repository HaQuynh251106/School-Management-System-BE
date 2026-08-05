DELETE FROM academic_training_plans plan
USING academic_years year
WHERE year.id = plan.academic_year_id
  AND year.code = '2000-2001'
  AND plan.status = 'DRAFT'
  AND NOT EXISTS (
      SELECT 1
      FROM academic_training_plan_subjects subject
      WHERE subject.plan_id = plan.id
  )
  AND NOT EXISTS (
      SELECT 1
      FROM academic_exam_schedules exam
      WHERE exam.plan_id = plan.id
  );

DELETE FROM academic_years year
WHERE year.code = '2000-2001'
  AND NOT EXISTS (
      SELECT 1 FROM semesters semester
      WHERE semester.academic_year_id = year.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM classes school_class
      WHERE school_class.academic_year_id = year.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM academic_training_plans plan
      WHERE plan.academic_year_id = year.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM fee_periods fee_period
      WHERE fee_period.academic_year_id = year.id
  );
