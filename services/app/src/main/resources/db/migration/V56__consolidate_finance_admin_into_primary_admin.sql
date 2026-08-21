-- Product roles remain exactly ADMIN, TEACHER, STUDENT and PARENT.
-- The legacy finance demo account represented an operational split, not a role.
-- Finance reconciliation and refund approval now belong to the primary ADMIN flow.
UPDATE public.users
SET status = 'DELETED',
    deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
    delete_reason = COALESCE(delete_reason, 'Đã gộp nghiệp vụ đối soát vào tài khoản Admin chính'),
    updated_at = CURRENT_TIMESTAMP,
    session_version = session_version + 1
WHERE lower(username) = 'admin.finance'
  AND role = 'ADMIN'
  AND status <> 'DELETED';

UPDATE public.refresh_tokens
SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
WHERE user_id IN (
    SELECT id FROM public.users WHERE lower(username) = 'admin.finance'
);

UPDATE public.user_devices
SET active = false,
    deactivated_at = COALESCE(deactivated_at, CURRENT_TIMESTAMP),
    deactivation_reason = COALESCE(deactivation_reason, 'Finance Admin merged into primary Admin')
WHERE user_id IN (
    SELECT id FROM public.users WHERE lower(username) = 'admin.finance'
)
  AND active = true;
