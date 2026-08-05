-- Identity phase 1: immediate session revocation, detailed RBAC and lifecycle metadata.

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS session_version integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone;

ALTER TABLE public.refresh_tokens
    ADD COLUMN IF NOT EXISTS session_version integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS replaced_by_token_id character varying(255);

ALTER TABLE public.user_devices
    ADD COLUMN IF NOT EXISTS last_ip_address character varying(255),
    ADD COLUMN IF NOT EXISTS last_user_agent character varying(1000);

UPDATE public.refresh_tokens
SET last_seen_at = COALESCE(last_seen_at, created_at),
    session_version = COALESCE(session_version, 0);

INSERT INTO public.permissions (id, code, module, name, description)
VALUES
    ('perm-identity-profile-self', 'IDENTITY_PROFILE_READ_SELF', 'identity', 'View own profile', 'Read the authenticated user profile'),
    ('perm-identity-password-self', 'IDENTITY_PASSWORD_CHANGE_SELF', 'identity', 'Change own password', 'Change the authenticated user password'),
    ('perm-identity-login-history', 'IDENTITY_LOGIN_HISTORY_READ', 'identity', 'View login history', 'View user login successes and failures'),
    ('perm-identity-session-any', 'IDENTITY_SESSION_MANAGE_ANY', 'identity', 'Manage all sessions', 'View and revoke sessions belonging to another user'),
    ('perm-identity-device-any', 'IDENTITY_DEVICE_MANAGE_ANY', 'identity', 'Manage all devices', 'View and deactivate devices belonging to another user'),
    ('perm-audit-read', 'AUDIT_READ', 'audit', 'View audit log', 'View sensitive system audit records')
ON CONFLICT (code) DO UPDATE
SET module = excluded.module,
    name = excluded.name,
    description = excluded.description,
    active = true;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || r.code || '-' || p.code, r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'ADMIN'
   OR (
       r.code IN ('TEACHER', 'STUDENT', 'PARENT')
       AND p.code IN (
           'IDENTITY_PROFILE_READ_SELF',
           'IDENTITY_PASSWORD_CHANGE_SELF',
           'IDENTITY_SESSION_MANAGE_SELF',
           'IDENTITY_DEVICE_MANAGE_SELF'
       )
   )
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device
    ON public.refresh_tokens (device_id, user_id)
    WHERE device_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_last_seen
    ON public.refresh_tokens (user_id, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_login_history_user_created
    ON public.login_history (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_roles_user
    ON public.user_roles (user_id);
CREATE INDEX IF NOT EXISTS idx_role_permissions_role
    ON public.role_permissions (role_id);

