-- Additive database foundation shared by Identity and later academic phases.

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS password_change_required boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS password_changed_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS deleted_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS deleted_by character varying(255),
    ADD COLUMN IF NOT EXISTS delete_reason character varying(1000),
    ADD COLUMN IF NOT EXISTS restored_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS restored_by character varying(255);

ALTER TABLE public.refresh_tokens
    ADD COLUMN IF NOT EXISTS last_seen_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS revoked_by character varying(255),
    ADD COLUMN IF NOT EXISTS revoked_reason character varying(255),
    ADD COLUMN IF NOT EXISTS device_id character varying(255);

ALTER TABLE public.user_devices
    ADD COLUMN IF NOT EXISTS deactivated_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS deactivated_by character varying(255),
    ADD COLUMN IF NOT EXISTS deactivation_reason character varying(255);

ALTER TABLE public.audit_logs
    ADD COLUMN IF NOT EXISTS ip_address character varying(255),
    ADD COLUMN IF NOT EXISTS user_agent character varying(1000),
    ADD COLUMN IF NOT EXISTS request_id character varying(255),
    ADD COLUMN IF NOT EXISTS before_data jsonb,
    ADD COLUMN IF NOT EXISTS after_data jsonb;

CREATE TABLE IF NOT EXISTS public.roles (
    id character varying(64) PRIMARY KEY,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(1000),
    system_role boolean NOT NULL DEFAULT true,
    active boolean NOT NULL DEFAULT true,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_roles_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS public.permissions (
    id character varying(128) PRIMARY KEY,
    code character varying(128) NOT NULL,
    module character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(1000),
    active boolean NOT NULL DEFAULT true,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS public.user_roles (
    id character varying(255) PRIMARY KEY,
    user_id character varying(255) NOT NULL,
    role_id character varying(64) NOT NULL,
    assigned_at timestamp with time zone NOT NULL DEFAULT now(),
    assigned_by character varying(255),
    CONSTRAINT uk_user_roles_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.role_permissions (
    id character varying(255) PRIMARY KEY,
    role_id character varying(64) NOT NULL,
    permission_id character varying(128) NOT NULL,
    granted_at timestamp with time zone NOT NULL DEFAULT now(),
    granted_by character varying(255),
    CONSTRAINT uk_role_permissions_role_permission UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE
);

INSERT INTO public.roles (id, code, name, description)
VALUES
    ('role-admin', 'ADMIN', 'Administrator', 'School system administrator'),
    ('role-teacher', 'TEACHER', 'Teacher', 'Teacher and homeroom teacher'),
    ('role-student', 'STUDENT', 'Student', 'Student portal user'),
    ('role-parent', 'PARENT', 'Parent', 'Parent portal user')
ON CONFLICT (code) DO UPDATE
SET name = excluded.name,
    description = excluded.description,
    active = true;

INSERT INTO public.permissions (id, code, module, name)
VALUES
    ('perm-identity-user-read', 'IDENTITY_USER_READ', 'identity', 'View users'),
    ('perm-identity-user-create', 'IDENTITY_USER_CREATE', 'identity', 'Create users'),
    ('perm-identity-user-update', 'IDENTITY_USER_UPDATE', 'identity', 'Update users'),
    ('perm-identity-user-lock', 'IDENTITY_USER_LOCK', 'identity', 'Lock and unlock users'),
    ('perm-identity-user-reset-password', 'IDENTITY_USER_RESET_PASSWORD', 'identity', 'Reset user passwords'),
    ('perm-identity-user-delete', 'IDENTITY_USER_DELETE', 'identity', 'Soft delete users'),
    ('perm-identity-user-restore', 'IDENTITY_USER_RESTORE', 'identity', 'Restore deleted users'),
    ('perm-identity-rbac-manage', 'IDENTITY_RBAC_MANAGE', 'identity', 'Manage roles and permissions'),
    ('perm-identity-session-self', 'IDENTITY_SESSION_MANAGE_SELF', 'identity', 'Manage own sessions'),
    ('perm-identity-device-self', 'IDENTITY_DEVICE_MANAGE_SELF', 'identity', 'Manage own devices')
ON CONFLICT (code) DO UPDATE
SET module = excluded.module,
    name = excluded.name,
    active = true;

INSERT INTO public.user_roles (id, user_id, role_id)
SELECT 'ur-' || u.id || '-' || lower(u.role), u.id, r.id
FROM public.users u
JOIN public.roles r ON r.code = u.role
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || r.code || '-' || p.code, r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'ADMIN'
   OR (p.code IN ('IDENTITY_SESSION_MANAGE_SELF', 'IDENTITY_DEVICE_MANAGE_SELF')
       AND r.code IN ('TEACHER', 'STUDENT', 'PARENT'))
   OR (p.code = 'IDENTITY_USER_READ' AND r.code = 'TEACHER')
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username_ci
    ON public.users (lower(username));
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email_ci
    ON public.users (lower(email))
    WHERE email IS NOT NULL AND trim(email) <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_student_code_ci
    ON public.users (lower(student_code))
    WHERE student_code IS NOT NULL AND trim(student_code) <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_teacher_code_ci
    ON public.users (lower(teacher_code))
    WHERE teacher_code IS NOT NULL AND trim(teacher_code) <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_devices_user_token
    ON public.user_devices (user_id, device_token);

CREATE INDEX IF NOT EXISTS idx_users_status_active
    ON public.users (status, role) WHERE status <> 'DELETED';
CREATE INDEX IF NOT EXISTS idx_users_deleted_at
    ON public.users (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_active_session
    ON public.refresh_tokens (user_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expiry
    ON public.refresh_tokens (expires_at);
CREATE INDEX IF NOT EXISTS idx_user_devices_last_seen
    ON public.user_devices (user_id, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_created_at
    ON public.audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_entity
    ON public.audit_logs (entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action
    ON public.audit_logs (module, action, created_at DESC);

ALTER TABLE public.users
    ADD CONSTRAINT ck_users_role
        CHECK (role IN ('ADMIN', 'TEACHER', 'STUDENT', 'PARENT')),
    ADD CONSTRAINT ck_users_status
        CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING', 'DELETED'));

ALTER TABLE public.grades
    ADD CONSTRAINT ck_grades_score
        CHECK (score IS NULL OR (score >= 0 AND score <= 10));

ALTER TABLE public.attendance_records
    ADD CONSTRAINT ck_attendance_status
        CHECK (status IS NULL OR status IN
            ('PRESENT', 'ABSENT_EXCUSED', 'ABSENT_UNEXCUSED', 'LATE'));

ALTER TABLE public.classes
    ADD CONSTRAINT fk_classes_academic_year
        FOREIGN KEY (academic_year_id) REFERENCES public.academic_years(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_classes_homeroom_teacher
        FOREIGN KEY (homeroom_teacher_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.semesters
    ADD CONSTRAINT fk_semesters_academic_year
        FOREIGN KEY (academic_year_id) REFERENCES public.academic_years(id) ON DELETE RESTRICT;

ALTER TABLE public.users
    ADD CONSTRAINT fk_users_current_class
        FOREIGN KEY (class_id) REFERENCES public.classes(id) ON DELETE SET NULL;

ALTER TABLE public.parent_student
    ADD CONSTRAINT fk_parent_student_parent
        FOREIGN KEY (parent_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_parent_student_student
        FOREIGN KEY (student_id) REFERENCES public.users(id) ON DELETE RESTRICT;

ALTER TABLE public.teacher_class_subjects
    ADD CONSTRAINT fk_tcs_teacher
        FOREIGN KEY (teacher_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tcs_class
        FOREIGN KEY (class_id) REFERENCES public.classes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tcs_subject
        FOREIGN KEY (subject_id) REFERENCES public.subjects(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tcs_semester
        FOREIGN KEY (semester_id) REFERENCES public.semesters(id) ON DELETE RESTRICT;

ALTER TABLE public.timetable_slots
    ADD CONSTRAINT fk_timetable_class
        FOREIGN KEY (class_id) REFERENCES public.classes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_timetable_subject
        FOREIGN KEY (subject_id) REFERENCES public.subjects(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_timetable_teacher
        FOREIGN KEY (teacher_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_timetable_semester
        FOREIGN KEY (semester_id) REFERENCES public.semesters(id) ON DELETE RESTRICT;

ALTER TABLE public.grades
    ADD CONSTRAINT fk_grades_student
        FOREIGN KEY (student_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_grades_subject
        FOREIGN KEY (subject_id) REFERENCES public.subjects(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_grades_semester
        FOREIGN KEY (semester_id) REFERENCES public.semesters(id) ON DELETE RESTRICT;

ALTER TABLE public.grade_change_logs
    ADD CONSTRAINT fk_grade_change_grade
        FOREIGN KEY (grade_id) REFERENCES public.grades(id) ON DELETE RESTRICT;

ALTER TABLE public.attendance_records
    ADD CONSTRAINT fk_attendance_student
        FOREIGN KEY (student_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_attendance_class
        FOREIGN KEY (class_id) REFERENCES public.classes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_attendance_slot
        FOREIGN KEY (slot_id) REFERENCES public.timetable_slots(id) ON DELETE RESTRICT;

ALTER TABLE public.assignments
    ADD CONSTRAINT fk_assignments_teacher
        FOREIGN KEY (teacher_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_assignments_class
        FOREIGN KEY (class_id) REFERENCES public.classes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_assignments_subject
        FOREIGN KEY (subject_id) REFERENCES public.subjects(id) ON DELETE RESTRICT;

ALTER TABLE public.assignment_submissions
    ADD CONSTRAINT fk_submissions_assignment
        FOREIGN KEY (assignment_id) REFERENCES public.assignments(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_submissions_student
        FOREIGN KEY (student_id) REFERENCES public.users(id) ON DELETE RESTRICT;

ALTER TABLE public.refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_refresh_tokens_device
        FOREIGN KEY (device_id) REFERENCES public.user_devices(id) ON DELETE SET NULL;

ALTER TABLE public.user_devices
    ADD CONSTRAINT fk_user_devices_user
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE public.password_reset_tokens
    ADD CONSTRAINT fk_password_reset_user
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE public.login_history
    ADD CONSTRAINT fk_login_history_user
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.invoice_items
    ADD CONSTRAINT fk_invoice_items_invoice
        FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE CASCADE;

ALTER TABLE public.payments
    ADD CONSTRAINT fk_payments_invoice
        FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE RESTRICT;

ALTER TABLE public.notifications
    ADD CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_id) REFERENCES public.users(id) ON DELETE RESTRICT;

ALTER TABLE public.chat_messages
    ADD CONSTRAINT fk_chat_sender
        FOREIGN KEY (sender_id) REFERENCES public.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_chat_recipient
        FOREIGN KEY (recipient_id) REFERENCES public.users(id) ON DELETE RESTRICT;
