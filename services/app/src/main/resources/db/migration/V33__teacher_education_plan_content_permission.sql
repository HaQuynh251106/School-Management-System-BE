INSERT INTO public.permissions (id, code, module, name, description)
VALUES (
    'perm-academic-plan-content-manage',
    'ACADEMIC_PLAN_CONTENT_MANAGE',
    'academic',
    'Quản lý nội dung kế hoạch môn học',
    'Giáo viên lập phân phối chương trình và kế hoạch kiểm tra cho đúng môn chuyên môn'
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    module = EXCLUDED.module,
    description = EXCLUDED.description,
    active = true;

INSERT INTO public.role_permissions (id, role_id, permission_id)
SELECT 'rp-' || r.code || '-' || p.code, r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code = 'ACADEMIC_PLAN_CONTENT_MANAGE'
WHERE r.code IN ('ADMIN', 'TEACHER')
ON CONFLICT (role_id, permission_id) DO NOTHING;
