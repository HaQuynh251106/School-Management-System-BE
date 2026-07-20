BEGIN;

-- Bộ dữ liệu kiểm thử tài chính nội bộ. Tất cả ID/mã đều có tiền tố TEST-TC
-- và có thể chạy lại an toàn mà không tạo bản ghi trùng.

INSERT INTO fee_periods
    (id, code, name, status, academic_year_id, apply_to_grades, due_date, created_at)
VALUES
    ('fp-test-tc-draft', 'TEST-TC-NHAP', '[TEST] Học phí tháng 9 - bản nháp', 'DRAFT', NULL, NULL, DATE '2026-08-31', NOW()),
    ('fp-test-tc-open', 'TEST-TC-THU', '[TEST] Học phí tháng 7 - đang thu', 'OPEN', NULL, NULL, DATE '2026-07-25', NOW()),
    ('fp-test-tc-closed', 'TEST-TC-DONG', '[TEST] Học phí tháng 6 - đã hoàn tất', 'CLOSED', NULL, NULL, DATE '2026-06-25', NOW())
ON CONFLICT (id) DO UPDATE SET
    code = EXCLUDED.code,
    name = EXCLUDED.name,
    status = EXCLUDED.status,
    apply_to_grades = EXCLUDED.apply_to_grades,
    due_date = EXCLUDED.due_date;

INSERT INTO fee_period_items (id, fee_period_id, name, amount, grade_level)
VALUES
    ('fpi-test-draft-1', 'fp-test-tc-draft', 'Học phí tháng 9', 1500000, NULL),
    ('fpi-test-draft-2', 'fp-test-tc-draft', 'Phí bán trú tháng 9', 450000, NULL),
    ('fpi-test-open-1', 'fp-test-tc-open', 'Học phí tháng 7', 1500000, NULL),
    ('fpi-test-open-2', 'fp-test-tc-open', 'Phí cơ sở vật chất', 250000, NULL),
    ('fpi-test-open-3', 'fp-test-tc-open', 'Bảo hiểm y tế', 100000, NULL),
    ('fpi-test-closed-1', 'fp-test-tc-closed', 'Học phí tháng 6', 1200000, NULL)
ON CONFLICT (id) DO UPDATE SET
    fee_period_id = EXCLUDED.fee_period_id,
    name = EXCLUDED.name,
    amount = EXCLUDED.amount,
    grade_level = EXCLUDED.grade_level;

-- Sáu hóa đơn đang thu: quá hạn, sắp hạn, tương lai, thu một phần và đã thu đủ.
INSERT INTO invoices
    (id, code, student_id, student_name, class_id, class_code, grade_level, parent_id, fee_period_id,
     total_amount, paid_amount, status, issued_at, due_date, version)
SELECT seed.id, seed.code, student.id, student.full_name,
       class_row.id, class_row.code, class_row.grade_level, relation.parent_id,
       'fp-test-tc-open', 1850000, seed.paid_amount, seed.status,
       TIMESTAMPTZ '2026-07-01 08:00:00+07', seed.due_date, 0
FROM (VALUES
    ('inv-test-open-1', 'TEST-TC-2026-001', 'hs.nguyenan',     0::bigint,       'PENDING', DATE '2026-07-15'),
    ('inv-test-open-2', 'TEST-TC-2026-002', 'hs.nguyenminhan',800000::bigint,  'PARTIAL', DATE '2026-07-25'),
    ('inv-test-open-3', 'TEST-TC-2026-003', 'hs.lequanghuy',  1850000::bigint, 'PAID',    DATE '2026-07-25'),
    ('inv-test-open-4', 'TEST-TC-2026-004', 'hs.tranthuha',   0::bigint,       'PENDING', DATE '2026-08-20'),
    ('inv-test-open-5', 'TEST-TC-2026-005', 'hs.dominhkhang', 500000::bigint,  'PARTIAL', DATE '2026-07-10'),
    ('inv-test-open-6', 'TEST-TC-2026-006', 'hs.phamngocmai', 0::bigint,       'PENDING', DATE '2026-07-22')
) AS seed(id, code, username, paid_amount, status, due_date)
JOIN users student ON student.username = seed.username AND student.role = 'STUDENT'
LEFT JOIN classes class_row ON class_row.id = student.class_id
LEFT JOIN parent_student relation
       ON relation.student_id = student.id AND relation.primary_contact = TRUE
ON CONFLICT (id) DO UPDATE SET
    code = EXCLUDED.code,
    student_id = EXCLUDED.student_id,
    student_name = EXCLUDED.student_name,
    class_id = EXCLUDED.class_id,
    class_code = EXCLUDED.class_code,
    grade_level = EXCLUDED.grade_level,
    parent_id = EXCLUDED.parent_id,
    fee_period_id = EXCLUDED.fee_period_id,
    total_amount = EXCLUDED.total_amount,
    paid_amount = EXCLUDED.paid_amount,
    status = EXCLUDED.status,
    due_date = EXCLUDED.due_date;

-- Sáu hóa đơn của đợt đã đóng, tất cả đã thanh toán đủ.
INSERT INTO invoices
    (id, code, student_id, student_name, class_id, class_code, grade_level, parent_id, fee_period_id,
     total_amount, paid_amount, status, issued_at, due_date, version)
SELECT 'inv-test-closed-' || ROW_NUMBER() OVER (ORDER BY student.username),
       'TEST-TC-DONG-' || LPAD(ROW_NUMBER() OVER (ORDER BY student.username)::text, 3, '0'),
       student.id, student.full_name, class_row.id, class_row.code, class_row.grade_level,
       relation.parent_id, 'fp-test-tc-closed',
       1200000, 1200000, 'PAID', TIMESTAMPTZ '2026-06-01 08:00:00+07',
       DATE '2026-06-25', 0
FROM users student
LEFT JOIN classes class_row ON class_row.id = student.class_id
LEFT JOIN parent_student relation
       ON relation.student_id = student.id AND relation.primary_contact = TRUE
WHERE student.role = 'STUDENT'
ORDER BY student.username
ON CONFLICT (id) DO UPDATE SET
    student_id = EXCLUDED.student_id,
    student_name = EXCLUDED.student_name,
    class_id = EXCLUDED.class_id,
    class_code = EXCLUDED.class_code,
    grade_level = EXCLUDED.grade_level,
    parent_id = EXCLUDED.parent_id,
    total_amount = EXCLUDED.total_amount,
    paid_amount = EXCLUDED.paid_amount,
    status = EXCLUDED.status;

INSERT INTO invoice_items (id, invoice_id, name, amount)
SELECT 'ii-' || id || '-hoc-phi', id, 'Học phí tháng 7', 1500000
FROM invoices WHERE fee_period_id = 'fp-test-tc-open'
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, amount = EXCLUDED.amount;

INSERT INTO invoice_items (id, invoice_id, name, amount)
SELECT 'ii-' || id || '-co-so', id, 'Phí cơ sở vật chất', 250000
FROM invoices WHERE fee_period_id = 'fp-test-tc-open'
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, amount = EXCLUDED.amount;

INSERT INTO invoice_items (id, invoice_id, name, amount)
SELECT 'ii-' || id || '-bao-hiem', id, 'Bảo hiểm y tế', 100000
FROM invoices WHERE fee_period_id = 'fp-test-tc-open'
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, amount = EXCLUDED.amount;

INSERT INTO invoice_items (id, invoice_id, name, amount)
SELECT 'ii-' || id || '-hoc-phi', id, 'Học phí tháng 6', 1200000
FROM invoices WHERE fee_period_id = 'fp-test-tc-closed'
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, amount = EXCLUDED.amount;

-- Lịch sử thu một phần/đã thu cho đợt đang mở.
INSERT INTO payments (id, invoice_id, amount, method, status, txn_ref, created_at, paid_at)
VALUES
    ('pay-test-open-2', 'inv-test-open-2', 800000, 'CASH', 'SUCCESS', 'TEST-CASH-002', TIMESTAMPTZ '2026-07-12 09:15:00+07', TIMESTAMPTZ '2026-07-12 09:15:00+07'),
    ('pay-test-open-3', 'inv-test-open-3', 1850000, 'VNPAY', 'SUCCESS', 'TEST-VNPAY-003', TIMESTAMPTZ '2026-07-08 14:20:00+07', TIMESTAMPTZ '2026-07-08 14:20:00+07'),
    ('pay-test-open-5', 'inv-test-open-5', 500000, 'MOMO', 'SUCCESS', 'TEST-MOMO-005', TIMESTAMPTZ '2026-07-09 19:30:00+07', TIMESTAMPTZ '2026-07-09 19:30:00+07')
ON CONFLICT (id) DO UPDATE SET
    invoice_id = EXCLUDED.invoice_id,
    amount = EXCLUDED.amount,
    method = EXCLUDED.method,
    status = EXCLUDED.status,
    txn_ref = EXCLUDED.txn_ref,
    paid_at = EXCLUDED.paid_at;

-- Lịch sử thanh toán của đợt đã đóng.
INSERT INTO payments (id, invoice_id, amount, method, status, txn_ref, created_at, paid_at)
SELECT 'pay-' || invoice.id, invoice.id, invoice.total_amount,
       CASE MOD(ROW_NUMBER() OVER (ORDER BY invoice.code), 3)
           WHEN 0 THEN 'CASH' WHEN 1 THEN 'VNPAY' ELSE 'MOMO' END,
       'SUCCESS', 'TEST-PAID-' || invoice.code,
       TIMESTAMPTZ '2026-06-18 10:00:00+07' + (ROW_NUMBER() OVER (ORDER BY invoice.code) * INTERVAL '1 hour'),
       TIMESTAMPTZ '2026-06-18 10:00:00+07' + (ROW_NUMBER() OVER (ORDER BY invoice.code) * INTERVAL '1 hour')
FROM invoices invoice
WHERE invoice.fee_period_id = 'fp-test-tc-closed'
ON CONFLICT (id) DO UPDATE SET
    invoice_id = EXCLUDED.invoice_id,
    amount = EXCLUDED.amount,
    method = EXCLUDED.method,
    status = EXCLUDED.status,
    txn_ref = EXCLUDED.txn_ref,
    paid_at = EXCLUDED.paid_at;

COMMIT;
