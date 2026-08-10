CREATE TABLE IF NOT EXISTS public.grade_configurations (
    id character varying(255) PRIMARY KEY,
    subject_id character varying(255) NOT NULL,
    semester_id character varying(255) NOT NULL,
    category_code character varying(255) NOT NULL,
    category_name character varying(255) NOT NULL,
    required_count integer NOT NULL DEFAULT 1,
    weight double precision NOT NULL DEFAULT 1,
    active boolean NOT NULL DEFAULT true,
    updated_by character varying(255),
    updated_at timestamp with time zone,
    CONSTRAINT ck_grade_config_required_count CHECK (required_count BETWEEN 1 AND 20),
    CONSTRAINT ck_grade_config_weight CHECK (weight > 0 AND weight <= 10),
    CONSTRAINT uk_grade_config_scope UNIQUE (subject_id, semester_id, category_code)
);

CREATE INDEX IF NOT EXISTS idx_grade_config_scope
    ON public.grade_configurations (subject_id, semester_id);

ALTER TABLE public.grades
    ADD COLUMN IF NOT EXISTS entry_index integer NOT NULL DEFAULT 1;

-- Legacy data allowed several marks in the same category without an explicit
-- position. Give every existing mark a deterministic position before adding
-- the uniqueness guard used by the configurable grade book.
WITH ranked_grades AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY student_id, subject_id, semester_id, category
               ORDER BY recorded_at NULLS LAST, id
           ) AS calculated_index
    FROM public.grades
)
UPDATE public.grades grade
SET entry_index = ranked.calculated_index
FROM ranked_grades ranked
WHERE grade.id = ranked.id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_grade_entry_scope
    ON public.grades (student_id, subject_id, semester_id, category, entry_index);
