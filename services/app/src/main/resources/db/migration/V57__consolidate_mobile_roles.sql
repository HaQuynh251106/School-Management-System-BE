-- Mobile/Web expose exactly four product roles. Academic and finance are
-- Admin capabilities, not separate user workspaces.
UPDATE users
SET role = 'ADMIN'
WHERE role IN ('ACADEMIC_STAFF', 'ACCOUNTANT');
