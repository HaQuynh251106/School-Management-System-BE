CREATE TABLE public.exam_periods (
    id varchar(255) PRIMARY KEY,
    code varchar(80) NOT NULL,
    name varchar(255) NOT NULL,
    academic_year_id varchar(255) NOT NULL REFERENCES public.academic_years(id),
    semester_id varchar(255) NOT NULL REFERENCES public.semesters(id),
    exam_type varchar(40) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    scope_grades varchar(80) NOT NULL,
    allow_subject_teacher_proctor boolean NOT NULL DEFAULT false,
    start_date date NOT NULL,
    end_date date NOT NULL,
    published_version_id varchar(255),
    created_by varchar(255) NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_exam_period_code_year UNIQUE (academic_year_id, code),
    CONSTRAINT ck_exam_period_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_exam_period_type CHECK (exam_type IN ('MIDTERM', 'FINAL', 'MAKEUP', 'PLACEMENT', 'OTHER')),
    CONSTRAINT ck_exam_period_dates CHECK (end_date >= start_date)
);

CREATE TABLE public.exam_schedule_versions (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL REFERENCES public.exam_periods(id) ON DELETE CASCADE,
    version_no integer NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    based_on_version_id varchar(255),
    change_reason varchar(1000),
    created_by varchar(255) NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL,
    published_by varchar(255),
    published_at timestamptz,
    CONSTRAINT uk_exam_schedule_version UNIQUE (exam_period_id, version_no),
    CONSTRAINT ck_exam_schedule_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

ALTER TABLE public.exam_periods
    ADD CONSTRAINT fk_exam_period_published_version
    FOREIGN KEY (published_version_id) REFERENCES public.exam_schedule_versions(id);

CREATE UNIQUE INDEX uk_exam_period_single_draft
    ON public.exam_schedule_versions(exam_period_id)
    WHERE status = 'DRAFT';

CREATE TABLE public.exam_sessions (
    id varchar(255) PRIMARY KEY,
    version_id varchar(255) NOT NULL REFERENCES public.exam_schedule_versions(id) ON DELETE CASCADE,
    subject_id varchar(255) NOT NULL REFERENCES public.subjects(id),
    grade_level varchar(16) NOT NULL,
    exam_date date NOT NULL,
    start_time time NOT NULL,
    duration_minutes integer NOT NULL,
    notes varchar(1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_exam_session_subject_grade UNIQUE (version_id, subject_id, grade_level),
    CONSTRAINT ck_exam_session_grade CHECK (grade_level IN ('K10', 'K11', 'K12')),
    CONSTRAINT ck_exam_session_duration CHECK (duration_minutes BETWEEN 15 AND 300)
);

CREATE INDEX idx_exam_session_slot
    ON public.exam_sessions(version_id, exam_date, start_time);

CREATE TABLE public.exam_room_assignments (
    id varchar(255) PRIMARY KEY,
    session_id varchar(255) NOT NULL REFERENCES public.exam_sessions(id) ON DELETE CASCADE,
    room_id varchar(255) NOT NULL REFERENCES public.rooms(id),
    capacity_snapshot integer NOT NULL,
    primary_proctor_id varchar(255) REFERENCES public.users(id),
    backup_proctor_id varchar(255) REFERENCES public.users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_exam_room_session UNIQUE (session_id, room_id),
    CONSTRAINT ck_exam_room_capacity CHECK (capacity_snapshot > 0),
    CONSTRAINT ck_exam_distinct_proctors CHECK (
        primary_proctor_id IS NULL OR backup_proctor_id IS NULL
        OR primary_proctor_id <> backup_proctor_id
    )
);

CREATE TABLE public.exam_room_students (
    id varchar(255) PRIMARY KEY,
    session_id varchar(255) NOT NULL REFERENCES public.exam_sessions(id) ON DELETE CASCADE,
    room_assignment_id varchar(255) NOT NULL REFERENCES public.exam_room_assignments(id) ON DELETE CASCADE,
    student_id varchar(255) NOT NULL REFERENCES public.users(id),
    student_code varchar(255),
    student_name varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL REFERENCES public.classes(id),
    class_code varchar(255) NOT NULL,
    seat_no integer NOT NULL,
    CONSTRAINT uk_exam_student_session UNIQUE (session_id, student_id),
    CONSTRAINT uk_exam_room_seat UNIQUE (room_assignment_id, seat_no),
    CONSTRAINT ck_exam_seat_no CHECK (seat_no > 0)
);

CREATE INDEX idx_exam_room_student_student
    ON public.exam_room_students(student_id);

CREATE TABLE public.exam_teacher_unavailability (
    id varchar(255) PRIMARY KEY,
    exam_period_id varchar(255) NOT NULL REFERENCES public.exam_periods(id) ON DELETE CASCADE,
    teacher_id varchar(255) NOT NULL REFERENCES public.users(id),
    unavailable_date date NOT NULL,
    start_time time,
    end_time time,
    reason varchar(1000) NOT NULL,
    created_by varchar(255) NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_exam_unavailability_time CHECK (
        (start_time IS NULL AND end_time IS NULL)
        OR (start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
    )
);

CREATE INDEX idx_exam_teacher_unavailable
    ON public.exam_teacher_unavailability(teacher_id, unavailable_date);

INSERT INTO public.permissions (id, code, module, name, description)
VALUES
    ('perm-academic-exam-schedule-read', 'ACADEMIC_EXAM_SCHEDULE_READ', 'academic', 'Xem lịch thi', 'Xem lịch thi đã được phát hành theo đúng vai trò'),
    ('perm-academic-exam-schedule-manage', 'ACADEMIC_EXAM_SCHEDULE_MANAGE', 'academic', 'Quản lý lịch thi và coi thi', 'Tạo đợt thi, xếp phòng, học sinh, giám thị và phát hành lịch thi')
ON CONFLICT (code) DO UPDATE
SET module = excluded.module,
    name = excluded.name,
    description = excluded.description,
    active = true;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || r.code || '-' || p.code, r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code = 'ACADEMIC_EXAM_SCHEDULE_READ'
WHERE r.code IN ('ADMIN', 'TEACHER', 'STUDENT', 'PARENT')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || r.code || '-' || p.code, r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code = 'ACADEMIC_EXAM_SCHEDULE_MANAGE'
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
