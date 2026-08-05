-- Phase 3: versioned annual training plans and curriculum details.

ALTER TABLE public.academic_training_plans
    DROP CONSTRAINT IF EXISTS uk_training_plan_year_grade;

ALTER TABLE public.academic_training_plans
    DROP CONSTRAINT IF EXISTS ck_training_plan_status;

ALTER TABLE public.academic_training_plans
    ADD COLUMN IF NOT EXISTS version_number integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS based_on_plan_id character varying(255),
    ADD COLUMN IF NOT EXISTS locked_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS locked_by character varying(255);

UPDATE public.academic_training_plans
SET status = 'LOCKED',
    locked_at = COALESCE(locked_at, updated_at)
WHERE status = 'CLOSED';

ALTER TABLE public.academic_training_plans
    ADD CONSTRAINT ck_training_plan_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'LOCKED')),
    ADD CONSTRAINT ck_training_plan_version
        CHECK (version_number >= 1),
    ADD CONSTRAINT fk_training_plan_base
        FOREIGN KEY (based_on_plan_id)
        REFERENCES public.academic_training_plans(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_training_plan_locker
        FOREIGN KEY (locked_by)
        REFERENCES public.users(id) ON DELETE SET NULL,
    ADD CONSTRAINT uk_training_plan_year_grade_version
        UNIQUE (academic_year_id, grade_level, version_number);

CREATE UNIQUE INDEX uk_training_plan_current_draft
    ON public.academic_training_plans (academic_year_id, grade_level)
    WHERE status = 'DRAFT';

CREATE TABLE public.academic_training_plan_stages (
    id character varying(255) PRIMARY KEY,
    plan_subject_id character varying(255) NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    sequence integer NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    target_periods integer NOT NULL,
    description character varying(1000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_training_stage_code UNIQUE (plan_subject_id, code),
    CONSTRAINT uk_training_stage_sequence UNIQUE (plan_subject_id, sequence),
    CONSTRAINT ck_training_stage_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_training_stage_periods CHECK (target_periods >= 1),
    CONSTRAINT ck_training_stage_dates CHECK (end_date >= start_date),
    CONSTRAINT fk_training_stage_subject FOREIGN KEY (plan_subject_id)
        REFERENCES public.academic_training_plan_subjects(id) ON DELETE CASCADE
);

CREATE TABLE public.academic_curriculum_items (
    id character varying(255) PRIMARY KEY,
    plan_subject_id character varying(255) NOT NULL,
    parent_id character varying(255),
    item_type character varying(24) NOT NULL,
    code character varying(64) NOT NULL,
    title character varying(500) NOT NULL,
    sequence integer NOT NULL,
    planned_periods integer NOT NULL DEFAULT 0,
    description character varying(2000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_curriculum_item_code UNIQUE (plan_subject_id, code),
    CONSTRAINT uk_curriculum_item_sequence UNIQUE (
        plan_subject_id, parent_id, sequence
    ),
    CONSTRAINT ck_curriculum_item_type
        CHECK (item_type IN ('CHAPTER', 'TOPIC', 'LESSON')),
    CONSTRAINT ck_curriculum_item_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_curriculum_item_periods CHECK (planned_periods >= 0),
    CONSTRAINT fk_curriculum_item_subject FOREIGN KEY (plan_subject_id)
        REFERENCES public.academic_training_plan_subjects(id) ON DELETE CASCADE,
    CONSTRAINT fk_curriculum_item_parent FOREIGN KEY (parent_id)
        REFERENCES public.academic_curriculum_items(id) ON DELETE CASCADE
);

CREATE TABLE public.academic_training_plan_special_weeks (
    id character varying(255) PRIMARY KEY,
    plan_subject_id character varying(255) NOT NULL,
    week_type character varying(24) NOT NULL,
    week_number integer NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(1000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_training_special_week UNIQUE (
        plan_subject_id, week_type, week_number
    ),
    CONSTRAINT ck_training_special_week_type
        CHECK (week_type IN ('EXAM', 'BUFFER')),
    CONSTRAINT ck_training_special_week_number
        CHECK (week_number BETWEEN 1 AND 30),
    CONSTRAINT fk_training_special_week_subject
        FOREIGN KEY (plan_subject_id)
        REFERENCES public.academic_training_plan_subjects(id) ON DELETE CASCADE
);

CREATE INDEX idx_training_stage_subject
    ON public.academic_training_plan_stages (plan_subject_id, sequence);
CREATE INDEX idx_curriculum_subject
    ON public.academic_curriculum_items (
        plan_subject_id, parent_id, sequence
    );
CREATE INDEX idx_training_special_week_subject
    ON public.academic_training_plan_special_weeks (
        plan_subject_id, week_number
    );

-- Preserve existing plans with a minimal editable curriculum skeleton.
INSERT INTO public.academic_training_plan_stages (
    id, plan_subject_id, code, name, sequence, start_date, end_date,
    target_periods, description
)
SELECT
    'stage-v11-' || md5(subject.id),
    subject.id,
    'GD1',
    'Toàn bộ giai đoạn',
    1,
    subject.start_date,
    subject.end_date,
    subject.total_periods,
    'Dữ liệu chuyển đổi từ kế hoạch trước Giai đoạn 3'
FROM public.academic_training_plan_subjects subject
ON CONFLICT (plan_subject_id, code) DO NOTHING;

INSERT INTO public.academic_curriculum_items (
    id, plan_subject_id, parent_id, item_type, code, title, sequence,
    planned_periods, description
)
SELECT
    'chapter-v11-' || md5(subject.id),
    subject.id,
    NULL,
    'CHAPTER',
    'CH1',
    'Chương trình học kỳ',
    1,
    0,
    'Khung chương được tạo khi nâng cấp dữ liệu'
FROM public.academic_training_plan_subjects subject
ON CONFLICT (plan_subject_id, code) DO NOTHING;

INSERT INTO public.academic_curriculum_items (
    id, plan_subject_id, parent_id, item_type, code, title, sequence,
    planned_periods, description
)
SELECT
    'topic-v11-' || md5(subject.id),
    subject.id,
    'chapter-v11-' || md5(subject.id),
    'TOPIC',
    'CD1',
    'Nội dung chính',
    1,
    0,
    'Khung chủ đề được tạo khi nâng cấp dữ liệu'
FROM public.academic_training_plan_subjects subject
ON CONFLICT (plan_subject_id, code) DO NOTHING;

INSERT INTO public.academic_curriculum_items (
    id, plan_subject_id, parent_id, item_type, code, title, sequence,
    planned_periods, description
)
SELECT
    'lesson-v11-' || md5(subject.id),
    subject.id,
    'topic-v11-' || md5(subject.id),
    'LESSON',
    'BH1',
    'Nội dung chương trình hiện có',
    1,
    subject.total_periods,
    'Cần tách thành các bài học chi tiết khi chỉnh sửa phiên bản mới'
FROM public.academic_training_plan_subjects subject
ON CONFLICT (plan_subject_id, code) DO NOTHING;

INSERT INTO public.academic_training_plan_special_weeks (
    id, plan_subject_id, week_type, week_number, name, description
)
SELECT
    'exam-week-v11-' || md5(subject.id),
    subject.id,
    'EXAM',
    GREATEST(
        1,
        LEAST(30, ((subject.end_date - subject.start_date) / 7))
    ),
    'Tuần kiểm tra',
    'Tuần kiểm tra được suy ra từ dữ liệu kế hoạch hiện có'
FROM public.academic_training_plan_subjects subject
ON CONFLICT (plan_subject_id, week_type, week_number) DO NOTHING;

INSERT INTO public.academic_training_plan_special_weeks (
    id, plan_subject_id, week_type, week_number, name, description
)
SELECT
    'buffer-week-v11-' || md5(subject.id),
    subject.id,
    'BUFFER',
    GREATEST(
        1,
        LEAST(30, ((subject.end_date - subject.start_date) / 7) + 1)
    ),
    'Tuần dự phòng',
    'Tuần dự phòng được suy ra từ dữ liệu kế hoạch hiện có'
FROM public.academic_training_plan_subjects subject
ON CONFLICT (plan_subject_id, week_type, week_number) DO NOTHING;
