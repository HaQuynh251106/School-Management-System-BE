-- Hóa đơn lịch sử từng được nhập số đã thu trực tiếp nhưng chưa có bút toán
-- thanh toán. Tạo bút toán số dư đầu kỳ để Dashboard, chi tiết hóa đơn và báo
-- cáo đối soát luôn cùng một nguồn sự thật. Script idempotent theo invoice id.
INSERT INTO payments (
    id, invoice_id, amount, method, status, txn_ref, created_at, paid_at
)
SELECT
    CONCAT('pay-opening-', i.id),
    i.id,
    i.paid_amount - COALESCE(p.recorded_amount, 0),
    'OPENING_BALANCE',
    'SUCCESS',
    CONCAT('OPENING-', i.code),
    COALESCE(i.issued_at, CURRENT_TIMESTAMP),
    COALESCE(i.issued_at, CURRENT_TIMESTAMP)
FROM invoices i
LEFT JOIN (
    SELECT invoice_id, SUM(amount) AS recorded_amount
    FROM payments
    WHERE status = 'SUCCESS'
    GROUP BY invoice_id
) p ON p.invoice_id = i.id
WHERE i.paid_amount > COALESCE(p.recorded_amount, 0)
  AND NOT EXISTS (
      SELECT 1 FROM payments existing
      WHERE existing.id = CONCAT('pay-opening-', i.id)
  );

