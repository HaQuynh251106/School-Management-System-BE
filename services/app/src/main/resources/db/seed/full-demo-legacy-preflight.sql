-- Compatibility bridge for databases whose initial tables were created by
-- Hibernate before Flyway V2 became authoritative. This file never inserts,
-- updates or deletes business data. It only restores the unique indexes that
-- V2 needs for its idempotent ON CONFLICT statements.
--
-- New databases do not have these tables yet, so every action is conditional.
-- Existing duplicate legacy rows deliberately make CREATE UNIQUE INDEX fail:
-- the reset script must stop instead of guessing which security row to keep.
DO $$
BEGIN
    IF to_regclass('public.roles') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.roles ALTER COLUMN active SET DEFAULT true';
        EXECUTE 'ALTER TABLE public.roles ALTER COLUMN system_role SET DEFAULT true';
        EXECUTE 'ALTER TABLE public.roles ALTER COLUMN created_at SET DEFAULT now()';
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS ux_legacy_roles_code '
             || 'ON public.roles (code)';
    END IF;
    IF to_regclass('public.permissions') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.permissions ALTER COLUMN active SET DEFAULT true';
        EXECUTE 'ALTER TABLE public.permissions ALTER COLUMN created_at SET DEFAULT now()';
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS ux_legacy_permissions_code '
             || 'ON public.permissions (code)';
    END IF;
    IF to_regclass('public.user_roles') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.user_roles ALTER COLUMN assigned_at SET DEFAULT now()';
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS ux_legacy_user_roles_user_role '
             || 'ON public.user_roles (user_id, role_id)';
    END IF;
    IF to_regclass('public.role_permissions') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.role_permissions ALTER COLUMN granted_at SET DEFAULT now()';
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS ux_legacy_role_permissions_role_permission '
             || 'ON public.role_permissions (role_id, permission_id)';
    END IF;
    IF to_regclass('public.grade_levels') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.grade_levels ALTER COLUMN active SET DEFAULT true';
    END IF;
END $$;
