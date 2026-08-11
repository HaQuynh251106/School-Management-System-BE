ALTER TABLE invoices ADD COLUMN IF NOT EXISTS refunded_amount BIGINT NOT NULL DEFAULT 0;

UPDATE invoices SET status = 'UNPAID' WHERE status = 'PENDING';
UPDATE invoices
SET status = 'OVERDUE'
WHERE paid_amount = 0
  AND due_date IS NOT NULL
  AND due_date < CURRENT_DATE
  AND status = 'UNPAID';

CREATE TABLE IF NOT EXISTS invoice_refunds (
    id VARCHAR(255) PRIMARY KEY,
    invoice_id VARCHAR(255) NOT NULL,
    amount BIGINT NOT NULL,
    method VARCHAR(32) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refund_invoice ON invoice_refunds(invoice_id);
