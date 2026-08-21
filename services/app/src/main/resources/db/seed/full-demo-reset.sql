BEGIN;
SELECT pg_advisory_xact_lock(hashtext('sse-full-demo-reset'));

-- Preserve every non-demo administrator. All other rows below are business or
-- demo data and are rebuilt deterministically by full-demo.sql.
CREATE TEMP TABLE fd_preserved_admins ON COMMIT DROP AS
SELECT *
FROM public.users
WHERE role = 'ADMIN'
  AND id NOT LIKE 'fd-%'
  AND username NOT LIKE 'demo.%';

DO $$
DECLARE
    reset_tables text;
BEGIN
    SELECT string_agg(format('public.%I', tablename), ', ' ORDER BY tablename)
    INTO reset_tables
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename NOT IN (
          'flyway_schema_history',
          'roles',
          'permissions',
          'role_permissions',
          'grade_levels',
          'exam_categories',
          'notification_templates'
      );

    IF reset_tables IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE ' || reset_tables || ' RESTART IDENTITY CASCADE';
    END IF;
END $$;

INSERT INTO public.users
SELECT * FROM fd_preserved_admins
ON CONFLICT (id) DO NOTHING;

-- Restore RBAC compatibility rows for the administrators retained above.
INSERT INTO public.user_roles (id, user_id, role_id)
SELECT 'ur-' || u.id || '-' || lower(u.role), u.id, r.id
FROM public.users u
JOIN public.roles r ON r.code = u.role
WHERE u.role = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;

COMMIT;
