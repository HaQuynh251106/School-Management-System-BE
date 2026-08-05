-- Phase 3 completion: education programs, combinations, weekly distribution,
-- assessment planning and an auditable approval workflow.

ALTER TABLE public.subjects
    ADD COLUMN IF NOT EXISTS subject_type character varying(32) NOT NULL DEFAULT 'MANDATORY',
    ADD COLUMN IF NOT EXISTS department_name character varying(255),
    ADD COLUMN IF NOT EXISTS assessment_method character varying(64) NOT NULL DEFAULT 'SCORE',
    ADD COLUMN IF NOT EXISTS facility_note character varying(500);

UPDATE public.subjects
SET subject_type = CASE
        WHEN upper(code) IN ('CHAOCO', 'SHL') THEN 'EDUCATIONAL_ACTIVITY'
        WHEN upper(code) IN ('PHYS', 'CHEM', 'BIO', 'IT', 'GEO', 'HIST') THEN 'OPTIONAL'
        ELSE 'MANDATORY'
    END,
    assessment_method = CASE
        WHEN upper(code) IN ('CHAOCO', 'SHL') THEN 'COMMENT'
        ELSE 'SCORE'
    END,
    department_name = COALESCE(department_name, CASE
        WHEN upper(code) = 'MATH' THEN 'Tổ Toán'
        WHEN upper(code) IN ('PHYS', 'CHEM', 'BIO') THEN 'Tổ Khoa học tự nhiên'
        WHEN upper(code) IN ('LIT', 'HIST', 'GEO', 'CIVIC') THEN 'Tổ Khoa học xã hội'
        WHEN upper(code) = 'ENG' THEN 'Tổ Ngoại ngữ'
        WHEN upper(code) = 'IT' THEN 'Tổ Tin học'
        ELSE 'Tổ Hoạt động giáo dục'
    END);

ALTER TABLE public.subjects DROP CONSTRAINT IF EXISTS ck_subject_type;
ALTER TABLE public.subjects ADD CONSTRAINT ck_subject_type CHECK (
    subject_type IN ('MANDATORY', 'OPTIONAL', 'SPECIALIZED', 'EDUCATIONAL_ACTIVITY')
);

ALTER TABLE public.classes
    ADD COLUMN IF NOT EXISTS expected_student_count integer,
    ADD COLUMN IF NOT EXISTS status character varying(24) NOT NULL DEFAULT 'ACTIVE';

UPDATE public.classes
SET expected_student_count = COALESCE(expected_student_count, max_students, 45);

CREATE TABLE public.education_programs (
    id character varying(255) PRIMARY KEY,
    code character varying(64) NOT NULL UNIQUE,
    name character varying(255) NOT NULL,
    start_year integer NOT NULL,
    description character varying(2000),
    status character varying(24) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_education_program_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

CREATE TABLE public.education_program_subjects (
    id character varying(255) PRIMARY KEY,
    program_id character varying(255) NOT NULL,
    grade_level character varying(8) NOT NULL,
    subject_id character varying(255) NOT NULL,
    subject_type character varying(32) NOT NULL,
    annual_periods integer NOT NULL,
    semester1_periods integer NOT NULL,
    semester2_periods integer NOT NULL,
    weekly_periods integer NOT NULL,
    required boolean NOT NULL DEFAULT true,
    notes character varying(1000),
    CONSTRAINT uk_program_subject UNIQUE (program_id, grade_level, subject_id),
    CONSTRAINT ck_program_subject_periods CHECK (
        annual_periods > 0 AND semester1_periods >= 0 AND semester2_periods >= 0
        AND annual_periods = semester1_periods + semester2_periods
        AND weekly_periods BETWEEN 1 AND 20
    ),
    CONSTRAINT fk_program_subject_program FOREIGN KEY (program_id)
        REFERENCES public.education_programs(id) ON DELETE CASCADE,
    CONSTRAINT fk_program_subject_grade FOREIGN KEY (grade_level)
        REFERENCES public.grade_levels(code) ON DELETE RESTRICT,
    CONSTRAINT fk_program_subject_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT
);

CREATE TABLE public.subject_combinations (
    id character varying(255) PRIMARY KEY,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    academic_year_id character varying(255) NOT NULL,
    grade_level character varying(8) NOT NULL,
    expected_class_count integer NOT NULL DEFAULT 1,
    max_students integer NOT NULL DEFAULT 45,
    status character varying(24) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_subject_combination UNIQUE (academic_year_id, grade_level, code),
    CONSTRAINT ck_subject_combination_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT fk_subject_combination_year FOREIGN KEY (academic_year_id)
        REFERENCES public.academic_years(id) ON DELETE CASCADE,
    CONSTRAINT fk_subject_combination_grade FOREIGN KEY (grade_level)
        REFERENCES public.grade_levels(code) ON DELETE RESTRICT
);

CREATE TABLE public.subject_combination_subjects (
    id character varying(255) PRIMARY KEY,
    combination_id character varying(255) NOT NULL,
    subject_id character varying(255) NOT NULL,
    CONSTRAINT uk_combination_subject UNIQUE (combination_id, subject_id),
    CONSTRAINT fk_combination_subject_combination FOREIGN KEY (combination_id)
        REFERENCES public.subject_combinations(id) ON DELETE CASCADE,
    CONSTRAINT fk_combination_subject_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT
);

CREATE TABLE public.class_subject_combinations (
    class_id character varying(255) PRIMARY KEY,
    combination_id character varying(255) NOT NULL,
    assigned_at timestamp with time zone NOT NULL DEFAULT now(),
    assigned_by character varying(255),
    CONSTRAINT fk_class_combination_class FOREIGN KEY (class_id)
        REFERENCES public.classes(id) ON DELETE CASCADE,
    CONSTRAINT fk_class_combination_combination FOREIGN KEY (combination_id)
        REFERENCES public.subject_combinations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_class_combination_actor FOREIGN KEY (assigned_by)
        REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE TABLE public.teacher_subject_capabilities (
    id character varying(255) PRIMARY KEY,
    teacher_id character varying(255) NOT NULL,
    subject_id character varying(255) NOT NULL,
    primary_subject boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_teacher_subject_capability UNIQUE (teacher_id, subject_id),
    CONSTRAINT fk_teacher_subject_teacher FOREIGN KEY (teacher_id)
        REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT fk_teacher_subject_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT
);

ALTER TABLE public.academic_training_plans
    ADD COLUMN IF NOT EXISTS program_id character varying(255),
    ADD COLUMN IF NOT EXISTS description character varying(2000),
    ADD COLUMN IF NOT EXISTS created_by character varying(255),
    ADD COLUMN IF NOT EXISTS submitted_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS submitted_by character varying(255),
    ADD COLUMN IF NOT EXISTS reviewed_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS reviewed_by character varying(255),
    ADD COLUMN IF NOT EXISTS approved_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS approved_by character varying(255),
    ADD COLUMN IF NOT EXISTS workflow_comment character varying(2000);

INSERT INTO public.education_programs (
    id, code, name, start_year, description, status
) VALUES (
    'program-gdpt-2018', 'GDPT2018', 'Chương trình giáo dục phổ thông 2018',
    2018, 'Chương trình mặc định được áp dụng cho khối 10, 11 và 12.', 'ACTIVE'
) ON CONFLICT (code) DO UPDATE SET
    name = excluded.name,
    description = excluded.description,
    status = 'ACTIVE',
    updated_at = now();

UPDATE public.academic_training_plans
SET program_id = 'program-gdpt-2018'
WHERE program_id IS NULL;

ALTER TABLE public.academic_training_plans
    DROP CONSTRAINT IF EXISTS ck_training_plan_status;
ALTER TABLE public.academic_training_plans
    ADD CONSTRAINT ck_training_plan_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'REVISION_REQUIRED', 'APPROVED',
        'PUBLISHED', 'ARCHIVED', 'LOCKED'
    )),
    ADD CONSTRAINT fk_training_plan_program FOREIGN KEY (program_id)
        REFERENCES public.education_programs(id) ON DELETE RESTRICT;

DROP INDEX IF EXISTS public.uk_training_plan_current_draft;
CREATE UNIQUE INDEX uk_training_plan_current_editable
    ON public.academic_training_plans (academic_year_id, grade_level)
    WHERE status IN ('DRAFT', 'REVISION_REQUIRED', 'SUBMITTED', 'APPROVED');

CREATE TABLE public.academic_curriculum_distributions (
    id character varying(255) PRIMARY KEY,
    plan_subject_id character varying(255) NOT NULL,
    curriculum_item_id character varying(255),
    week_number integer NOT NULL,
    content_type character varying(24) NOT NULL,
    title character varying(500) NOT NULL,
    periods integer NOT NULL,
    notes character varying(1000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_curriculum_distribution UNIQUE (
        plan_subject_id, week_number, content_type, title
    ),
    CONSTRAINT ck_curriculum_distribution_week CHECK (week_number BETWEEN 1 AND 30),
    CONSTRAINT ck_curriculum_distribution_periods CHECK (periods BETWEEN 1 AND 20),
    CONSTRAINT ck_curriculum_distribution_type CHECK (content_type IN (
        'THEORY', 'PRACTICE', 'REVIEW', 'ASSESSMENT', 'PROJECT', 'EXPERIENCE', 'BUFFER'
    )),
    CONSTRAINT fk_curriculum_distribution_subject FOREIGN KEY (plan_subject_id)
        REFERENCES public.academic_training_plan_subjects(id) ON DELETE CASCADE,
    CONSTRAINT fk_curriculum_distribution_item FOREIGN KEY (curriculum_item_id)
        REFERENCES public.academic_curriculum_items(id) ON DELETE SET NULL
);

CREATE TABLE public.academic_assessment_plans (
    id character varying(255) PRIMARY KEY,
    plan_id character varying(255) NOT NULL,
    semester_id character varying(255) NOT NULL,
    class_id character varying(255),
    subject_id character varying(255) NOT NULL,
    assessment_type character varying(32) NOT NULL,
    week_number integer NOT NULL,
    duration_minutes integer NOT NULL,
    teacher_id character varying(255),
    notes character varying(1000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_assessment_plan UNIQUE (
        plan_id, semester_id, class_id, subject_id, assessment_type, week_number
    ),
    CONSTRAINT ck_assessment_plan_type CHECK (assessment_type IN (
        'REGULAR', 'MIDTERM', 'FINAL', 'PRACTICE', 'PROJECT'
    )),
    CONSTRAINT ck_assessment_plan_week CHECK (week_number BETWEEN 1 AND 30),
    CONSTRAINT ck_assessment_plan_duration CHECK (duration_minutes BETWEEN 15 AND 300),
    CONSTRAINT fk_assessment_plan_plan FOREIGN KEY (plan_id)
        REFERENCES public.academic_training_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_plan_semester FOREIGN KEY (semester_id)
        REFERENCES public.semesters(id) ON DELETE RESTRICT,
    CONSTRAINT fk_assessment_plan_class FOREIGN KEY (class_id)
        REFERENCES public.classes(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_plan_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_assessment_plan_teacher FOREIGN KEY (teacher_id)
        REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE TABLE public.academic_plan_approval_history (
    id character varying(255) PRIMARY KEY,
    plan_id character varying(255) NOT NULL,
    action character varying(32) NOT NULL,
    from_status character varying(24),
    to_status character varying(24) NOT NULL,
    actor_id character varying(255) NOT NULL,
    comment character varying(2000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT fk_plan_approval_plan FOREIGN KEY (plan_id)
        REFERENCES public.academic_training_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_approval_actor FOREIGN KEY (actor_id)
        REFERENCES public.users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_program_subject_grade ON public.education_program_subjects(program_id, grade_level);
CREATE INDEX idx_combination_scope ON public.subject_combinations(academic_year_id, grade_level);
CREATE INDEX idx_curriculum_distribution_subject_week ON public.academic_curriculum_distributions(plan_subject_id, week_number);
CREATE INDEX idx_assessment_plan_scope ON public.academic_assessment_plans(plan_id, semester_id, week_number);
CREATE INDEX idx_plan_approval_history ON public.academic_plan_approval_history(plan_id, created_at);

-- Build the baseline program from the real subject catalog. Existing plan totals
-- win; otherwise a conservative 70-period annual default is used.
INSERT INTO public.education_program_subjects (
    id, program_id, grade_level, subject_id, subject_type, annual_periods,
    semester1_periods, semester2_periods, weekly_periods, required, notes
)
SELECT
    'eps-' || lower(g.code) || '-' || lower(s.code),
    'program-gdpt-2018', g.code, s.id, s.subject_type,
    COALESCE(stats.total_periods, CASE WHEN s.subject_type = 'EDUCATIONAL_ACTIVITY' THEN 35 ELSE 70 END),
    COALESCE(stats.hk1_periods, CASE WHEN s.subject_type = 'EDUCATIONAL_ACTIVITY' THEN 18 ELSE 35 END),
    COALESCE(stats.hk2_periods, CASE WHEN s.subject_type = 'EDUCATIONAL_ACTIVITY' THEN 17 ELSE 35 END),
    COALESCE(stats.weekly_periods, CASE WHEN s.subject_type = 'EDUCATIONAL_ACTIVITY' THEN 1 ELSE 2 END),
    s.subject_type IN ('MANDATORY', 'EDUCATIONAL_ACTIVITY'),
    'Cấu hình nền; có thể điều chỉnh trong GĐ3.'
FROM public.grade_levels g
CROSS JOIN public.subjects s
LEFT JOIN LATERAL (
    SELECT
        sum(pts.total_periods)::integer AS total_periods,
        sum(CASE WHEN sm.sequence = 1 THEN pts.total_periods ELSE 0 END)::integer AS hk1_periods,
        sum(CASE WHEN sm.sequence = 2 THEN pts.total_periods ELSE 0 END)::integer AS hk2_periods,
        max(pts.weekly_periods)::integer AS weekly_periods
    FROM public.academic_training_plan_subjects pts
    JOIN public.academic_training_plans p ON p.id = pts.plan_id
    JOIN public.semesters sm ON sm.id = pts.semester_id
    WHERE p.grade_level = g.code AND pts.subject_id = s.id
) stats ON true
WHERE s.active = true
ON CONFLICT (program_id, grade_level, subject_id) DO NOTHING;

-- Create two usable combinations per grade/year. A1-A5 use KHTN; A6-A10 use KHXH.
INSERT INTO public.subject_combinations (
    id, code, name, academic_year_id, grade_level, expected_class_count,
    max_students, status
)
SELECT 'comb-' || ay.id || '-' || lower(g.code) || '-khtn', 'KHTN',
       'Khoa học tự nhiên', ay.id, g.code, 5, 45, 'ACTIVE'
FROM public.academic_years ay CROSS JOIN public.grade_levels g
ON CONFLICT (academic_year_id, grade_level, code) DO NOTHING;

INSERT INTO public.subject_combinations (
    id, code, name, academic_year_id, grade_level, expected_class_count,
    max_students, status
)
SELECT 'comb-' || ay.id || '-' || lower(g.code) || '-khxh', 'KHXH',
       'Khoa học xã hội', ay.id, g.code, 5, 45, 'ACTIVE'
FROM public.academic_years ay CROSS JOIN public.grade_levels g
ON CONFLICT (academic_year_id, grade_level, code) DO NOTHING;

INSERT INTO public.subject_combination_subjects (id, combination_id, subject_id)
SELECT 'cs-' || md5(c.id || s.id), c.id, s.id
FROM public.subject_combinations c
JOIN public.subjects s ON (
    (c.code = 'KHTN' AND upper(s.code) IN ('PHYS', 'CHEM', 'BIO', 'IT')) OR
    (c.code = 'KHXH' AND upper(s.code) IN ('HIST', 'GEO', 'CIVIC', 'IT'))
)
ON CONFLICT (combination_id, subject_id) DO NOTHING;

INSERT INTO public.class_subject_combinations (class_id, combination_id)
SELECT c.id,
       CASE WHEN substring(c.code from 'A([0-9]+)')::integer <= 5
            THEN 'comb-' || c.academic_year_id || '-' || lower(c.grade_level) || '-khtn'
            ELSE 'comb-' || c.academic_year_id || '-' || lower(c.grade_level) || '-khxh'
       END
FROM public.classes c
WHERE c.code ~ 'A([0-9]+)$'
ON CONFLICT (class_id) DO NOTHING;

INSERT INTO public.teacher_subject_capabilities (
    id, teacher_id, subject_id, primary_subject, active
)
SELECT 'tsc-' || md5(u.id || s.id), u.id, s.id, true, true
FROM public.users u
JOIN public.subjects s ON lower(trim(u.main_subject)) IN (
    lower(s.id), lower(s.code), lower(s.name)
)
WHERE u.role = 'TEACHER' AND u.status = 'ACTIVE'
ON CONFLICT (teacher_id, subject_id) DO NOTHING;

INSERT INTO public.permissions (id, code, module, name, description)
VALUES
    ('perm-academic-program-manage', 'ACADEMIC_PROGRAM_MANAGE', 'academic', 'Quản lý chương trình giáo dục', 'Quản lý chương trình, cấu hình môn và tổ hợp môn'),
    ('perm-academic-plan-submit', 'ACADEMIC_PLAN_SUBMIT', 'academic', 'Gửi duyệt kế hoạch', 'Gửi kế hoạch giáo dục để kiểm tra'),
    ('perm-academic-plan-review', 'ACADEMIC_PLAN_REVIEW', 'academic', 'Kiểm tra kế hoạch', 'Yêu cầu chỉnh sửa hoặc chuyển kế hoạch sang bước phê duyệt'),
    ('perm-academic-plan-approve', 'ACADEMIC_PLAN_APPROVE', 'academic', 'Phê duyệt kế hoạch', 'Phê duyệt và công bố kế hoạch giáo dục')
ON CONFLICT (code) DO UPDATE SET
    module = excluded.module, name = excluded.name,
    description = excluded.description, active = true;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || r.code || '-' || p.code, r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code IN (
    'ACADEMIC_PROGRAM_MANAGE', 'ACADEMIC_PLAN_SUBMIT',
    'ACADEMIC_PLAN_REVIEW', 'ACADEMIC_PLAN_APPROVE'
)
WHERE r.code = 'ADMIN'
   OR (r.code = 'TEACHER' AND p.code = 'ACADEMIC_PLAN_SUBMIT')
ON CONFLICT (role_id, permission_id) DO NOTHING;
