-- Phase 4: automatic timetable drafts, publication workflow and real lesson progress.

ALTER TABLE public.subjects
    ADD COLUMN IF NOT EXISTS required_room_type varchar(24) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE public.rooms
    ADD COLUMN IF NOT EXISTS room_type varchar(24) NOT NULL DEFAULT 'GENERAL';

UPDATE public.subjects SET required_room_type = 'COMPUTER'
WHERE upper(code) IN ('IT', 'ICT', 'TIN');
UPDATE public.subjects SET required_room_type = 'LAB'
WHERE upper(code) IN ('PHYS', 'CHEM', 'BIO');
UPDATE public.subjects SET required_room_type = 'GYM'
WHERE upper(code) IN ('PE', 'SPORT');
UPDATE public.rooms SET room_type = 'COMPUTER' WHERE upper(code) LIKE 'IT%';
UPDATE public.rooms SET room_type = 'LAB' WHERE upper(code) LIKE 'LAB%';

ALTER TABLE public.subjects DROP CONSTRAINT IF EXISTS ck_subject_room_type;
ALTER TABLE public.subjects ADD CONSTRAINT ck_subject_room_type CHECK (
    required_room_type IN ('GENERAL', 'LAB', 'COMPUTER', 'GYM', 'MUSIC', 'ART')
);
ALTER TABLE public.rooms DROP CONSTRAINT IF EXISTS ck_room_type;
ALTER TABLE public.rooms ADD CONSTRAINT ck_room_type CHECK (
    room_type IN ('GENERAL', 'LAB', 'COMPUTER', 'GYM', 'MUSIC', 'ART')
);

CREATE TABLE IF NOT EXISTS public.timetable_schedules (
    id varchar(255) PRIMARY KEY,
    academic_year_id varchar(255) NOT NULL,
    semester_id varchar(255) NOT NULL,
    scope_grade_level varchar(8),
    name varchar(255) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    teaching_days varchar(64) NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI',
    first_period integer NOT NULL DEFAULT 1,
    last_period integer NOT NULL DEFAULT 6,
    max_periods_per_day integer NOT NULL DEFAULT 6,
    max_progress_gap_days integer NOT NULL DEFAULT 2,
    max_progress_gap_periods integer NOT NULL DEFAULT 2,
    max_curriculum_gap_lessons integer NOT NULL DEFAULT 1,
    solve_seconds integer NOT NULL DEFAULT 10,
    solver_score varchar(128),
    hard_violation_count integer NOT NULL DEFAULT 0,
    warning_count integer NOT NULL DEFAULT 0,
    generation_summary text,
    generated_at timestamp with time zone,
    generated_by varchar(255),
    published_at timestamp with time zone,
    published_by varchar(255),
    locked_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_timetable_schedule_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'LOCKED')),
    CONSTRAINT ck_timetable_schedule_periods CHECK (
        first_period BETWEEN 1 AND 12 AND last_period BETWEEN first_period AND 12
        AND max_periods_per_day BETWEEN 1 AND 12
    ),
    CONSTRAINT ck_timetable_schedule_thresholds CHECK (
        max_progress_gap_days BETWEEN 0 AND 14
        AND max_progress_gap_periods BETWEEN 0 AND 20
        AND max_curriculum_gap_lessons BETWEEN 0 AND 10
        AND solve_seconds BETWEEN 1 AND 120
    ),
    CONSTRAINT fk_timetable_schedule_year FOREIGN KEY (academic_year_id)
        REFERENCES public.academic_years(id) ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_schedule_semester FOREIGN KEY (semester_id)
        REFERENCES public.semesters(id) ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_schedule_grade FOREIGN KEY (scope_grade_level)
        REFERENCES public.grade_levels(code) ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_schedule_generator FOREIGN KEY (generated_by)
        REFERENCES public.users(id) ON DELETE SET NULL,
    CONSTRAINT fk_timetable_schedule_publisher FOREIGN KEY (published_by)
        REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_timetable_schedule_scope
    ON public.timetable_schedules (semester_id, scope_grade_level, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_timetable_schedule_published_scope
    ON public.timetable_schedules (semester_id, COALESCE(scope_grade_level, 'ALL'))
    WHERE status = 'PUBLISHED';

CREATE TABLE IF NOT EXISTS public.timetable_draft_slots (
    id varchar(255) PRIMARY KEY,
    schedule_id varchar(255) NOT NULL,
    assignment_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    subject_name varchar(255) NOT NULL,
    teacher_id varchar(255) NOT NULL,
    teacher_name varchar(255) NOT NULL,
    room_id varchar(255),
    room_code varchar(255),
    day_of_week varchar(8) NOT NULL,
    period_no integer NOT NULL,
    start_time varchar(16),
    end_time varchar(16),
    semester_id varchar(255) NOT NULL,
    lesson_index integer NOT NULL DEFAULT 1,
    source varchar(24) NOT NULL DEFAULT 'AUTO',
    pinned boolean NOT NULL DEFAULT false,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_timetable_draft_day CHECK (day_of_week IN ('MON','TUE','WED','THU','FRI','SAT','SUN')),
    CONSTRAINT ck_timetable_draft_period CHECK (period_no BETWEEN 1 AND 12),
    CONSTRAINT ck_timetable_draft_source CHECK (source IN ('AUTO','MANUAL','MOVED')),
    CONSTRAINT fk_timetable_draft_schedule FOREIGN KEY (schedule_id)
        REFERENCES public.timetable_schedules(id) ON DELETE CASCADE,
    CONSTRAINT fk_timetable_draft_assignment FOREIGN KEY (assignment_id)
        REFERENCES public.teacher_class_subjects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_draft_class FOREIGN KEY (class_id)
        REFERENCES public.classes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_draft_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_draft_teacher FOREIGN KEY (teacher_id)
        REFERENCES public.users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_draft_room FOREIGN KEY (room_id)
        REFERENCES public.rooms(id) ON DELETE SET NULL,
    CONSTRAINT fk_timetable_draft_semester FOREIGN KEY (semester_id)
        REFERENCES public.semesters(id) ON DELETE RESTRICT,
    CONSTRAINT uk_timetable_draft_lesson UNIQUE (schedule_id, assignment_id, lesson_index),
    CONSTRAINT uk_timetable_draft_class_slot UNIQUE (schedule_id, class_id, day_of_week, period_no),
    CONSTRAINT uk_timetable_draft_teacher_slot UNIQUE (schedule_id, teacher_id, day_of_week, period_no),
    CONSTRAINT uk_timetable_draft_room_slot UNIQUE (schedule_id, room_id, day_of_week, period_no)
);
CREATE INDEX IF NOT EXISTS idx_timetable_draft_schedule_class
    ON public.timetable_draft_slots (schedule_id, class_id, day_of_week, period_no);

ALTER TABLE public.timetable_slots
    ADD COLUMN IF NOT EXISTS source_schedule_id varchar(255);
ALTER TABLE public.timetable_slots DROP CONSTRAINT IF EXISTS fk_timetable_slot_source_schedule;
ALTER TABLE public.timetable_slots ADD CONSTRAINT fk_timetable_slot_source_schedule
    FOREIGN KEY (source_schedule_id) REFERENCES public.timetable_schedules(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_timetable_slot_source_schedule
    ON public.timetable_slots (source_schedule_id);

CREATE TABLE IF NOT EXISTS public.class_lesson_progress (
    id varchar(255) PRIMARY KEY,
    academic_year_id varchar(255) NOT NULL,
    semester_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    curriculum_item_id varchar(255) NOT NULL,
    lesson_date date NOT NULL,
    planned_periods integer NOT NULL,
    completed_periods integer NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'COMPLETED',
    teacher_id varchar(255) NOT NULL,
    notes varchar(2000),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_lesson_progress_periods CHECK (
        planned_periods >= 1 AND completed_periods BETWEEN 0 AND planned_periods
    ),
    CONSTRAINT ck_lesson_progress_status CHECK (status IN ('PLANNED','PARTIAL','COMPLETED','CANCELLED')),
    CONSTRAINT uk_lesson_progress UNIQUE (
        class_id, subject_id, semester_id, curriculum_item_id, lesson_date
    ),
    CONSTRAINT fk_lesson_progress_year FOREIGN KEY (academic_year_id)
        REFERENCES public.academic_years(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_progress_semester FOREIGN KEY (semester_id)
        REFERENCES public.semesters(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_progress_class FOREIGN KEY (class_id)
        REFERENCES public.classes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_progress_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_progress_curriculum FOREIGN KEY (curriculum_item_id)
        REFERENCES public.academic_curriculum_items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_progress_teacher FOREIGN KEY (teacher_id)
        REFERENCES public.users(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_lesson_progress_compare
    ON public.class_lesson_progress (semester_id, subject_id, class_id, lesson_date);

CREATE TABLE IF NOT EXISTS public.timetable_makeup_proposals (
    id varchar(255) PRIMARY KEY,
    schedule_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    subject_id varchar(255) NOT NULL,
    teacher_id varchar(255) NOT NULL,
    room_code varchar(255),
    missed_date date NOT NULL,
    missed_period_no integer NOT NULL,
    proposed_date date,
    proposed_period_no integer,
    reason varchar(1000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PROPOSED',
    reviewed_by varchar(255),
    reviewed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_makeup_status CHECK (status IN ('PROPOSED','APPROVED','REJECTED','UNSCHEDULED')),
    CONSTRAINT uk_makeup_missed_slot UNIQUE (schedule_id, class_id, missed_date, missed_period_no),
    CONSTRAINT fk_makeup_schedule FOREIGN KEY (schedule_id)
        REFERENCES public.timetable_schedules(id) ON DELETE CASCADE,
    CONSTRAINT fk_makeup_class FOREIGN KEY (class_id)
        REFERENCES public.classes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_makeup_subject FOREIGN KEY (subject_id)
        REFERENCES public.subjects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_makeup_teacher FOREIGN KEY (teacher_id)
        REFERENCES public.users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_makeup_reviewer FOREIGN KEY (reviewed_by)
        REFERENCES public.users(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_makeup_schedule_status
    ON public.timetable_makeup_proposals (schedule_id, status, missed_date);

INSERT INTO public.permissions (id, code, module, name, description)
VALUES
    ('perm-academic-timetable-read', 'ACADEMIC_TIMETABLE_READ', 'academic', 'Xem thời khóa biểu', 'Xem lịch nháp, lịch đã phát hành và cảnh báo'),
    ('perm-academic-timetable-manage', 'ACADEMIC_TIMETABLE_MANAGE', 'academic', 'Xếp thời khóa biểu', 'Tạo lịch tự động và chỉnh lịch nháp'),
    ('perm-academic-timetable-publish', 'ACADEMIC_TIMETABLE_PUBLISH', 'academic', 'Phát hành thời khóa biểu', 'Khóa và phát hành lịch cho người dùng'),
    ('perm-academic-progress-read', 'ACADEMIC_PROGRESS_READ', 'academic', 'Xem tiến độ giảng dạy', 'So sánh tiến độ thực tế giữa các lớp'),
    ('perm-academic-progress-update', 'ACADEMIC_PROGRESS_UPDATE', 'academic', 'Cập nhật tiến độ giảng dạy', 'Ghi nhận bài học và số tiết đã hoàn thành')
ON CONFLICT (code) DO UPDATE SET
    module = excluded.module, name = excluded.name,
    description = excluded.description, active = true;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || role.code || '-' || permission.code, role.id, permission.id
FROM public.roles role
JOIN public.permissions permission ON permission.code IN (
    'ACADEMIC_TIMETABLE_READ', 'ACADEMIC_TIMETABLE_MANAGE',
    'ACADEMIC_TIMETABLE_PUBLISH', 'ACADEMIC_PROGRESS_READ',
    'ACADEMIC_PROGRESS_UPDATE'
)
WHERE role.code = 'ADMIN'
   OR (role.code = 'TEACHER' AND permission.code IN (
       'ACADEMIC_TIMETABLE_READ', 'ACADEMIC_PROGRESS_READ',
       'ACADEMIC_PROGRESS_UPDATE'
   ))
   OR (role.code IN ('STUDENT','PARENT')
       AND permission.code = 'ACADEMIC_TIMETABLE_READ')
ON CONFLICT (role_id, permission_id) DO NOTHING;
