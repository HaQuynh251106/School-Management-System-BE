-- A new program starts as a draft. Activating one program archives the previous
-- active program, and this index protects the invariant at database level.
ALTER TABLE public.education_programs
    ALTER COLUMN status SET DEFAULT 'DRAFT';

WITH active_programs AS (
    SELECT program.id,
           row_number() OVER (
               ORDER BY count(program_subject.id) DESC,
                        program.start_year DESC,
                        program.updated_at DESC,
                        program.id
           ) AS position
    FROM public.education_programs program
    LEFT JOIN public.education_program_subjects program_subject
        ON program_subject.program_id = program.id
    WHERE program.status = 'ACTIVE'
    GROUP BY program.id
)
UPDATE public.education_programs program
SET status = 'ARCHIVED', updated_at = now()
FROM active_programs active
WHERE program.id = active.id AND active.position > 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_education_programs_single_active
    ON public.education_programs ((status))
    WHERE status = 'ACTIVE';
