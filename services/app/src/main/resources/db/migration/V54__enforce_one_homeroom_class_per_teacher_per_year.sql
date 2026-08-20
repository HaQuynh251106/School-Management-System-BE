-- Historical demo data assigned the same teacher to several classes. Preserve
-- the first class in each year, move duplicates to unused active teachers, and
-- enforce the business rule for all subsequent writes.
WITH ranked_assignments AS (
    SELECT class.id AS class_id,
           class.academic_year_id,
           class.homeroom_teacher_id,
           row_number() OVER (
               PARTITION BY class.academic_year_id, class.homeroom_teacher_id
               ORDER BY class.code, class.id
           ) AS assignment_rank
    FROM public.classes class
    WHERE class.homeroom_teacher_id IS NOT NULL
), duplicates AS (
    SELECT class_id, academic_year_id,
           row_number() OVER (
               PARTITION BY academic_year_id ORDER BY class_id
           ) AS replacement_rank
    FROM ranked_assignments
    WHERE assignment_rank > 1
), available_teachers AS (
    SELECT year.id AS academic_year_id,
           teacher.id AS teacher_id,
           row_number() OVER (
               PARTITION BY year.id ORDER BY teacher.id
           ) AS replacement_rank
    FROM public.academic_years year
    CROSS JOIN public.users teacher
    WHERE teacher.role = 'TEACHER'
      AND teacher.status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1
          FROM public.classes assigned
          WHERE assigned.academic_year_id = year.id
            AND assigned.homeroom_teacher_id = teacher.id
      )
), replacements AS (
    SELECT duplicate.class_id, available.teacher_id
    FROM duplicates duplicate
    LEFT JOIN available_teachers available
      ON available.academic_year_id = duplicate.academic_year_id
     AND available.replacement_rank = duplicate.replacement_rank
)
UPDATE public.classes class
SET homeroom_teacher_id = replacement.teacher_id
FROM replacements replacement
WHERE class.id = replacement.class_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_class_year_homeroom_teacher
    ON public.classes(academic_year_id, homeroom_teacher_id)
    WHERE homeroom_teacher_id IS NOT NULL;
