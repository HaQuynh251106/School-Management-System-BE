-- Phase 2: academic structure, enrolment and annual training plans.

CREATE TABLE IF NOT EXISTS public.grade_levels (
    code character varying(8) PRIMARY KEY,
    name character varying(64) NOT NULL,
    numeric_level integer NOT NULL UNIQUE,
    display_order integer NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_grade_levels_numeric CHECK (numeric_level IN (10, 11, 12))
);

INSERT INTO public.grade_levels (code, name, numeric_level, display_order)
VALUES
    ('K10', 'Khối 10', 10, 10),
    ('K11', 'Khối 11', 11, 11),
    ('K12', 'Khối 12', 12, 12)
ON CONFLICT (code) DO UPDATE
SET name = excluded.name,
    numeric_level = excluded.numeric_level,
    display_order = excluded.display_order,
    active = true;

ALTER TABLE public.subjects
    ADD COLUMN IF NOT EXISTS coefficient double precision NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;

ALTER TABLE public.rooms
    ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;

ALTER TABLE public.school_holidays
    ADD COLUMN IF NOT EXISTS academic_year_id character varying(255),
    ADD COLUMN IF NOT EXISTS end_date date;

UPDATE public.school_holidays SET end_date = date WHERE end_date IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_semesters_year_sequence
    ON public.semesters (academic_year_id, sequence);
CREATE UNIQUE INDEX IF NOT EXISTS uk_subjects_code_ci
    ON public.subjects (lower(code));
CREATE UNIQUE INDEX IF NOT EXISTS uk_rooms_code_ci
    ON public.rooms (lower(code));
CREATE INDEX IF NOT EXISTS idx_classes_year_grade
    ON public.classes (academic_year_id, grade_level, code);
CREATE INDEX IF NOT EXISTS idx_school_holidays_year_date
    ON public.school_holidays (academic_year_id, date);

ALTER TABLE public.classes
    ADD CONSTRAINT fk_classes_grade_level
        FOREIGN KEY (grade_level) REFERENCES public.grade_levels(code) ON DELETE RESTRICT;

ALTER TABLE public.school_holidays
    ADD CONSTRAINT fk_school_holidays_academic_year
        FOREIGN KEY (academic_year_id) REFERENCES public.academic_years(id) ON DELETE CASCADE;

CREATE TABLE public.academic_training_plans (
    id character varying(255) PRIMARY KEY,
    academic_year_id character varying(255) NOT NULL,
    grade_level character varying(8) NOT NULL,
    name character varying(255) NOT NULL,
    status character varying(24) NOT NULL DEFAULT 'DRAFT',
    max_progress_gap_days integer NOT NULL DEFAULT 2,
    published_at timestamp with time zone,
    published_by character varying(255),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_training_plan_year_grade UNIQUE (academic_year_id, grade_level),
    CONSTRAINT ck_training_plan_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    CONSTRAINT ck_training_plan_gap CHECK (max_progress_gap_days BETWEEN 0 AND 14),
    CONSTRAINT fk_training_plan_year FOREIGN KEY (academic_year_id)
        REFERENCES public.academic_years(id) ON DELETE RESTRICT,
    CONSTRAINT fk_training_plan_grade FOREIGN KEY (grade_level)
        REFERENCES public.grade_levels(code) ON DELETE RESTRICT,
    CONSTRAINT fk_training_plan_publisher FOREIGN KEY (published_by)
        REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE TABLE public.academic_training_plan_subjects (
    id character varying(255) PRIMARY KEY,
    plan_id character varying(255) NOT NULL,
    semester_id character varying(255) NOT NULL,
    subject_id character varying(255) NOT NULL,
    weekly_periods integer NOT NULL,
    total_periods integer NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    exam_required boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_training_plan_subject UNIQUE (plan_id, semester_id, subject_id),
    CONSTRAINT ck_training_subject_periods CHECK (
        weekly_periods BETWEEN 1 AND 20 AND total_periods BETWEEN 1 AND 300
    ),
    CONSTRAINT ck_training_subject_dates CHECK (end_date >= start_date),
    CONSTRAINT fk_training_subject_plan FOREIGN KEY (plan_id)
        REFERENCES public.academic_training_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_training_subject_semester FOREIGN KEY (semester_id)
        REFERENCES public.semesters(id) ON DELETE RESTRICT,
    CONSTRAINT fk_training_subject_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT
);

CREATE TABLE public.academic_exam_schedules (
    id character varying(255) PRIMARY KEY,
    plan_id character varying(255) NOT NULL,
    semester_id character varying(255) NOT NULL,
    subject_id character varying(255) NOT NULL,
    grade_level character varying(8) NOT NULL,
    name character varying(255) NOT NULL,
    exam_date date NOT NULL,
    start_time time NOT NULL,
    duration_minutes integer NOT NULL,
    room_id character varying(255),
    proctor_teacher_id character varying(255),
    status character varying(24) NOT NULL DEFAULT 'PLANNED',
    notes character varying(1000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_exam_plan_subject_slot UNIQUE (
        plan_id, semester_id, subject_id, exam_date, start_time
    ),
    CONSTRAINT ck_exam_duration CHECK (duration_minutes BETWEEN 15 AND 300),
    CONSTRAINT ck_exam_status CHECK (status IN ('PLANNED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT fk_exam_plan FOREIGN KEY (plan_id)
        REFERENCES public.academic_training_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_semester FOREIGN KEY (semester_id)
        REFERENCES public.semesters(id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_grade FOREIGN KEY (grade_level)
        REFERENCES public.grade_levels(code) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_room FOREIGN KEY (room_id)
        REFERENCES public.rooms(id) ON DELETE SET NULL,
    CONSTRAINT fk_exam_proctor FOREIGN KEY (proctor_teacher_id)
        REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE INDEX idx_training_plan_year ON public.academic_training_plans (academic_year_id);
CREATE INDEX idx_training_subject_plan ON public.academic_training_plan_subjects (plan_id, semester_id);
CREATE INDEX idx_exam_schedule_date ON public.academic_exam_schedules (exam_date, start_time);
CREATE INDEX idx_exam_schedule_proctor ON public.academic_exam_schedules (proctor_teacher_id, exam_date);
CREATE INDEX idx_exam_schedule_room ON public.academic_exam_schedules (room_id, exam_date);

INSERT INTO public.student_class_enrollments (
    id, academic_year_id, class_id, student_id, student_code, student_name,
    enrollment_type, status, enrolled_by, enrolled_at
)
SELECT
    'enr-v6-' || u.id,
    c.academic_year_id,
    c.id,
    u.id,
    u.student_code,
    u.full_name,
    'BASELINE',
    'ACTIVE',
    'SYSTEM',
    now()
FROM public.users u
JOIN public.classes c ON c.id = u.class_id
WHERE u.role = 'STUDENT'
  AND u.status <> 'DELETED'
ON CONFLICT (academic_year_id, student_id) DO UPDATE
SET class_id = excluded.class_id,
    student_code = excluded.student_code,
    student_name = excluded.student_name,
    status = 'ACTIVE';

UPDATE public.classes c
SET student_count = counts.total
FROM (
    SELECT academic_year_id, class_id, count(*)::integer AS total
    FROM public.student_class_enrollments
    WHERE status = 'ACTIVE'
    GROUP BY academic_year_id, class_id
) counts
WHERE counts.academic_year_id = c.academic_year_id
  AND counts.class_id = c.id;

INSERT INTO public.permissions (id, code, module, name, description)
VALUES
    ('perm-academic-structure-read', 'ACADEMIC_STRUCTURE_READ', 'academic', 'Xem cơ cấu đào tạo', 'Xem năm học, học kỳ, khối, lớp, môn, phòng và ngày nghỉ'),
    ('perm-academic-structure-manage', 'ACADEMIC_STRUCTURE_MANAGE', 'academic', 'Quản lý cơ cấu đào tạo', 'Tạo và cập nhật dữ liệu nền đào tạo'),
    ('perm-academic-enrollment-manage', 'ACADEMIC_ENROLLMENT_MANAGE', 'academic', 'Quản lý phân lớp', 'Phân lớp và chuyển lớp thủ công cho học sinh'),
    ('perm-academic-plan-read', 'ACADEMIC_PLAN_READ', 'academic', 'Xem kế hoạch đào tạo', 'Xem kế hoạch môn học và lịch thi dự kiến'),
    ('perm-academic-plan-manage', 'ACADEMIC_PLAN_MANAGE', 'academic', 'Quản lý kế hoạch đào tạo', 'Soạn và công bố kế hoạch đào tạo'),
    ('perm-academic-exam-plan-manage', 'ACADEMIC_EXAM_PLAN_MANAGE', 'academic', 'Quản lý lịch thi dự kiến', 'Tạo và điều chỉnh lịch thi, phòng thi và giám thị')
ON CONFLICT (code) DO UPDATE
SET module = excluded.module,
    name = excluded.name,
    description = excluded.description,
    active = true;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || r.code || '-' || p.code, r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code IN (
    'ACADEMIC_STRUCTURE_READ',
    'ACADEMIC_STRUCTURE_MANAGE',
    'ACADEMIC_ENROLLMENT_MANAGE',
    'ACADEMIC_PLAN_READ',
    'ACADEMIC_PLAN_MANAGE',
    'ACADEMIC_EXAM_PLAN_MANAGE'
)
WHERE r.code = 'ADMIN'
   OR (r.code = 'TEACHER' AND p.code IN (
       'ACADEMIC_STRUCTURE_READ',
       'ACADEMIC_PLAN_READ',
       'ACADEMIC_PLAN_MANAGE',
       'ACADEMIC_EXAM_PLAN_MANAGE'
   ))
   OR (r.code IN ('STUDENT', 'PARENT') AND p.code IN (
       'ACADEMIC_STRUCTURE_READ',
       'ACADEMIC_PLAN_READ'
   ))
ON CONFLICT (role_id, permission_id) DO NOTHING;
