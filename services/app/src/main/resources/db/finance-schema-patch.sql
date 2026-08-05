-- P0 finance integrity patch. Safe to run repeatedly.

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS note varchar(500);

-- Preserve payment history while removing duplicate invoices. The earliest
-- invoice is canonical. Successful payments on later duplicate invoices are
-- retained as REVERSED because they represent duplicate sandbox collections.
WITH ranked AS (
    SELECT id,
           first_value(id) OVER (
               PARTITION BY fee_period_id, student_id
               ORDER BY issued_at NULLS LAST, id
           ) AS canonical_id,
           row_number() OVER (
               PARTITION BY fee_period_id, student_id
               ORDER BY issued_at NULLS LAST, id
           ) AS row_no
    FROM invoices
),
duplicates AS (
    SELECT id AS duplicate_id, canonical_id
    FROM ranked
    WHERE row_no > 1
)
UPDATE payments payment
SET invoice_id = duplicate.canonical_id,
    status = CASE WHEN payment.status = 'SUCCESS' THEN 'REVERSED' ELSE payment.status END,
    note = concat_ws(
        ' | ',
        nullif(payment.note, ''),
        'Reversed during P0 duplicate-invoice cleanup; original invoice=' || duplicate.duplicate_id
    )
FROM duplicates duplicate
WHERE payment.invoice_id = duplicate.duplicate_id;

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY fee_period_id, student_id
               ORDER BY issued_at NULLS LAST, id
           ) AS row_no
    FROM invoices
)
DELETE FROM invoice_items item
USING ranked duplicate
WHERE duplicate.row_no > 1
  AND item.invoice_id = duplicate.id;

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY fee_period_id, student_id
               ORDER BY issued_at NULLS LAST, id
           ) AS row_no
    FROM invoices
)
DELETE FROM invoices invoice
USING ranked duplicate
WHERE duplicate.row_no > 1
  AND invoice.id = duplicate.id;

-- Rebuild invoice balances from authoritative successful payment rows.
UPDATE invoices invoice
SET paid_amount = LEAST(
        invoice.total_amount,
        COALESCE((
            SELECT SUM(payment.amount)
            FROM payments payment
            WHERE payment.invoice_id = invoice.id
              AND payment.status = 'SUCCESS'
        ), 0)
    ),
    status = CASE
        WHEN COALESCE((
            SELECT SUM(payment.amount)
            FROM payments payment
            WHERE payment.invoice_id = invoice.id
              AND payment.status = 'SUCCESS'
        ), 0) >= invoice.total_amount THEN 'PAID'
        WHEN COALESCE((
            SELECT SUM(payment.amount)
            FROM payments payment
            WHERE payment.invoice_id = invoice.id
              AND payment.status = 'SUCCESS'
        ), 0) > 0 THEN 'PARTIAL'
        WHEN invoice.due_date IS NOT NULL AND invoice.due_date < CURRENT_DATE THEN 'OVERDUE'
        ELSE 'PENDING'
    END
WHERE invoice.status NOT IN ('CANCELLED', 'VOID');

CREATE UNIQUE INDEX IF NOT EXISTS uk_fee_period_code_ci
    ON fee_periods (lower(code));

CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_period_student
    ON invoices (fee_period_id, student_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_code
    ON invoices (code);

CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_txn_ref
    ON payments (txn_ref)
    WHERE txn_ref IS NOT NULL;

-- P1: normalized targeting and fee-period lifecycle.
ALTER TABLE fee_periods
    ADD COLUMN IF NOT EXISTS target_type varchar(20),
    ADD COLUMN IF NOT EXISTS fee_type varchar(30),
    ADD COLUMN IF NOT EXISTS semester_id varchar(255),
    ADD COLUMN IF NOT EXISTS published_at timestamptz,
    ADD COLUMN IF NOT EXISTS closed_at timestamptz,
    ADD COLUMN IF NOT EXISTS cancelled_at timestamptz,
    ADD COLUMN IF NOT EXISTS cancellation_reason varchar(500);

ALTER TABLE fee_period_items
    ADD COLUMN IF NOT EXISTS target_type varchar(20);

ALTER TABLE invoice_items
    ADD COLUMN IF NOT EXISTS fee_period_item_id varchar(255),
    ADD COLUMN IF NOT EXISTS source_target_type varchar(20);

CREATE TABLE IF NOT EXISTS fee_period_targets (
    id varchar(255) PRIMARY KEY,
    fee_period_id varchar(255) NOT NULL,
    target_type varchar(20) NOT NULL,
    target_id varchar(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS fee_period_item_targets (
    id varchar(255) PRIMARY KEY,
    fee_period_item_id varchar(255) NOT NULL,
    target_type varchar(20) NOT NULL,
    target_id varchar(255) NOT NULL
);

INSERT INTO fee_period_targets (id, fee_period_id, target_type, target_id)
SELECT 'fpt-legacy-' || md5(period.id || ':' || upper(trim(target_id))),
       period.id,
       'GRADE',
       upper(trim(target_id))
FROM fee_periods period
CROSS JOIN LATERAL regexp_split_to_table(period.apply_to_grades, ',') AS target_id
WHERE period.apply_to_grades IS NOT NULL
  AND trim(target_id) <> ''
ON CONFLICT DO NOTHING;

INSERT INTO fee_period_item_targets (id, fee_period_item_id, target_type, target_id)
SELECT 'fpit-legacy-' || md5(item.id || ':' || upper(trim(item.grade_level))),
       item.id,
       'GRADE',
       upper(trim(item.grade_level))
FROM fee_period_items item
WHERE item.grade_level IS NOT NULL
  AND trim(item.grade_level) <> ''
ON CONFLICT DO NOTHING;

UPDATE fee_periods period
SET target_type = CASE
    WHEN EXISTS (SELECT 1 FROM fee_period_targets target WHERE target.fee_period_id = period.id) THEN 'GRADE'
    ELSE 'ALL'
END
WHERE period.target_type IS NULL OR trim(period.target_type) = '';

UPDATE fee_periods
SET fee_type = 'OTHER'
WHERE fee_type IS NULL OR trim(fee_type) = '';

UPDATE fee_period_items item
SET target_type = CASE
    WHEN EXISTS (SELECT 1 FROM fee_period_item_targets target WHERE target.fee_period_item_id = item.id) THEN 'GRADE'
    ELSE 'ALL'
END
WHERE item.target_type IS NULL OR trim(item.target_type) = '';

ALTER TABLE fee_periods
    ALTER COLUMN target_type SET DEFAULT 'ALL',
    ALTER COLUMN target_type SET NOT NULL,
    ALTER COLUMN fee_type SET DEFAULT 'OTHER',
    ALTER COLUMN fee_type SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fee_period_type_semester
    ON fee_periods (fee_type, semester_id);

ALTER TABLE fee_period_items
    ALTER COLUMN target_type SET DEFAULT 'ALL',
    ALTER COLUMN target_type SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_fee_period_target
    ON fee_period_targets (fee_period_id, target_type, target_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_fee_period_item_target
    ON fee_period_item_targets (fee_period_item_id, target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_fee_period_target_period
    ON fee_period_targets (fee_period_id);

CREATE INDEX IF NOT EXISTS idx_fee_period_item_target_item
    ON fee_period_item_targets (fee_period_item_id);

ALTER TABLE fee_period_targets
    DROP CONSTRAINT IF EXISTS fk_fee_period_target_period;
ALTER TABLE fee_period_targets
    ADD CONSTRAINT fk_fee_period_target_period
    FOREIGN KEY (fee_period_id) REFERENCES fee_periods(id) ON DELETE CASCADE;

ALTER TABLE fee_period_item_targets
    DROP CONSTRAINT IF EXISTS fk_fee_period_item_target_item;
ALTER TABLE fee_period_item_targets
    ADD CONSTRAINT fk_fee_period_item_target_item
    FOREIGN KEY (fee_period_item_id) REFERENCES fee_period_items(id) ON DELETE CASCADE;

ALTER TABLE fee_periods
    DROP CONSTRAINT IF EXISTS ck_fee_period_target_type;
ALTER TABLE fee_periods
    ADD CONSTRAINT ck_fee_period_target_type
    CHECK (target_type IN ('ALL', 'GRADE', 'CLASS', 'STUDENT'));

ALTER TABLE fee_period_items
    DROP CONSTRAINT IF EXISTS ck_fee_period_item_target_type;
ALTER TABLE fee_period_items
    ADD CONSTRAINT ck_fee_period_item_target_type
    CHECK (target_type IN ('ALL', 'GRADE', 'CLASS', 'STUDENT'));

ALTER TABLE fee_periods
    DROP CONSTRAINT IF EXISTS ck_fee_period_status;
ALTER TABLE fee_periods
    ADD CONSTRAINT ck_fee_period_status
    CHECK (status IN ('DRAFT', 'OPEN', 'PUBLISHED', 'CLOSED', 'CANCELLED'));

-- Existing OPEN periods that already have invoices were issued before the
-- explicit PUBLISHED state existed.
UPDATE fee_periods period
SET status = 'PUBLISHED',
    published_at = COALESCE(
        period.published_at,
        (SELECT MIN(invoice.issued_at) FROM invoices invoice WHERE invoice.fee_period_id = period.id),
        CURRENT_TIMESTAMP
    )
WHERE period.status = 'OPEN'
  AND EXISTS (SELECT 1 FROM invoices invoice WHERE invoice.fee_period_id = period.id);

-- P2: payment intent and provider callback ledger.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS updated_at timestamptz;

UPDATE payments
SET updated_at = COALESCE(updated_at, paid_at, created_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;

CREATE TABLE IF NOT EXISTS payment_gateway_transactions (
    id varchar(255) PRIMARY KEY,
    payment_id varchar(255),
    provider varchar(30) NOT NULL,
    merchant_txn_ref varchar(255) NOT NULL,
    provider_transaction_id varchar(255),
    request_payload text,
    response_payload text,
    signature_valid boolean,
    processed boolean NOT NULL DEFAULT false,
    callback_count integer NOT NULL DEFAULT 0,
    error_code varchar(100),
    error_message varchar(500),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz,
    last_callback_at timestamptz,
    processed_at timestamptz
);

ALTER TABLE payment_gateway_transactions
    ADD COLUMN IF NOT EXISTS payment_id varchar(255),
    ADD COLUMN IF NOT EXISTS provider varchar(30),
    ADD COLUMN IF NOT EXISTS merchant_txn_ref varchar(255),
    ADD COLUMN IF NOT EXISTS provider_transaction_id varchar(255),
    ADD COLUMN IF NOT EXISTS request_payload text,
    ADD COLUMN IF NOT EXISTS response_payload text,
    ADD COLUMN IF NOT EXISTS signature_valid boolean,
    ADD COLUMN IF NOT EXISTS processed boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS callback_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_code varchar(100),
    ADD COLUMN IF NOT EXISTS error_message varchar(500),
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_callback_at timestamptz,
    ADD COLUMN IF NOT EXISTS processed_at timestamptz;

ALTER TABLE payment_gateway_transactions
    ALTER COLUMN provider SET NOT NULL,
    ALTER COLUMN merchant_txn_ref SET NOT NULL,
    ALTER COLUMN processed SET NOT NULL,
    ALTER COLUMN callback_count SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_gateway_tx_payment
    ON payment_gateway_transactions (payment_id);

DROP INDEX IF EXISTS idx_gateway_tx_provider_ref;

CREATE UNIQUE INDEX IF NOT EXISTS uk_gateway_tx_provider_ref
    ON payment_gateway_transactions (provider, merchant_txn_ref);

CREATE UNIQUE INDEX IF NOT EXISTS uk_gateway_tx_provider_transaction
    ON payment_gateway_transactions (provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

ALTER TABLE payment_gateway_transactions
    DROP CONSTRAINT IF EXISTS fk_gateway_tx_payment;
ALTER TABLE payment_gateway_transactions
    ADD CONSTRAINT fk_gateway_tx_payment
    FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE SET NULL;

ALTER TABLE payment_gateway_transactions
    DROP CONSTRAINT IF EXISTS ck_gateway_tx_callback_count;
ALTER TABLE payment_gateway_transactions
    ADD CONSTRAINT ck_gateway_tx_callback_count CHECK (callback_count >= 0);

ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS ck_payment_status;
ALTER TABLE payments
    ADD CONSTRAINT ck_payment_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REVERSED', 'EXPIRED'));

-- A paid invoice must not keep alternative payment attempts actionable.
UPDATE payments p
SET status = 'EXPIRED',
    note = COALESCE(NULLIF(p.note, ''), 'Hóa đơn đã được thanh toán bằng giao dịch khác'),
    updated_at = CURRENT_TIMESTAMP
FROM invoices i
WHERE i.id = p.invoice_id
  AND i.status = 'PAID'
  AND p.status = 'PENDING';

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS auto_provisioned boolean NOT NULL DEFAULT false;
UPDATE payments
SET auto_provisioned = false
WHERE auto_provisioned IS NULL;
ALTER TABLE payments
    ALTER COLUMN auto_provisioned SET DEFAULT false,
    ALTER COLUMN auto_provisioned SET NOT NULL;
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS bank_transfer_content varchar(255);
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS bank_qr_url varchar(1000);

-- P3: manual MB transfer receipt submitted by parent and reviewed by Admin.
CREATE TABLE IF NOT EXISTS payment_proofs (
    id varchar(255) PRIMARY KEY,
    payment_id varchar(255) NOT NULL,
    invoice_id varchar(255) NOT NULL,
    invoice_code varchar(255),
    parent_id varchar(255),
    student_id varchar(255) NOT NULL,
    student_code varchar(255),
    student_name varchar(255),
    amount bigint NOT NULL,
    file_id varchar(255) NOT NULL,
    file_name varchar(255),
    content_type varchar(160),
    size_bytes bigint NOT NULL,
    status varchar(24) NOT NULL,
    submitted_by varchar(255) NOT NULL,
    submitted_at timestamptz NOT NULL,
    transferred_at timestamptz,
    bank_transaction_code varchar(100),
    reviewed_by varchar(255),
    reviewed_at timestamptz,
    review_reason varchar(500)
);

ALTER TABLE payment_proofs
    ADD COLUMN IF NOT EXISTS transferred_at timestamptz;
ALTER TABLE payment_proofs
    ADD COLUMN IF NOT EXISTS bank_transaction_code varchar(100);
ALTER TABLE payment_proofs
    ALTER COLUMN transferred_at DROP NOT NULL;
ALTER TABLE payment_proofs
    ALTER COLUMN bank_transaction_code DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_proof_payment ON payment_proofs (payment_id);
CREATE INDEX IF NOT EXISTS idx_payment_proof_parent ON payment_proofs (parent_id);
CREATE INDEX IF NOT EXISTS idx_payment_proof_status ON payment_proofs (status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_proof_file ON payment_proofs (file_id);

ALTER TABLE payment_proofs DROP CONSTRAINT IF EXISTS fk_payment_proof_payment;
ALTER TABLE payment_proofs
    ADD CONSTRAINT fk_payment_proof_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE;
ALTER TABLE payment_proofs DROP CONSTRAINT IF EXISTS fk_payment_proof_invoice;
ALTER TABLE payment_proofs
    ADD CONSTRAINT fk_payment_proof_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE;
ALTER TABLE payment_proofs DROP CONSTRAINT IF EXISTS fk_payment_proof_file;
ALTER TABLE payment_proofs
    ADD CONSTRAINT fk_payment_proof_file FOREIGN KEY (file_id) REFERENCES stored_files(id) ON DELETE RESTRICT;
ALTER TABLE payment_proofs DROP CONSTRAINT IF EXISTS ck_payment_proof_status;
UPDATE payment_proofs SET status = 'RETRY_REQUIRED' WHERE status = 'REJECTED';
ALTER TABLE payment_proofs
    ADD CONSTRAINT ck_payment_proof_status CHECK (status IN ('SUBMITTED', 'APPROVED', 'RETRY_REQUIRED'));
ALTER TABLE payment_proofs DROP CONSTRAINT IF EXISTS ck_payment_proof_amount;
ALTER TABLE payment_proofs ADD CONSTRAINT ck_payment_proof_amount CHECK (amount > 0);
ALTER TABLE payment_proofs DROP CONSTRAINT IF EXISTS ck_payment_proof_size;
ALTER TABLE payment_proofs ADD CONSTRAINT ck_payment_proof_size CHECK (size_bytes > 0 AND size_bytes <= 5242880);

-- P4.1: immutable receipt metadata. The PDF itself is stored in MinIO.
CREATE TABLE IF NOT EXISTS payment_receipts (
    id varchar(255) PRIMARY KEY,
    receipt_number varchar(80) NOT NULL,
    payment_id varchar(255) NOT NULL,
    invoice_id varchar(255) NOT NULL,
    invoice_code varchar(255),
    student_id varchar(255) NOT NULL,
    student_code varchar(255),
    student_name varchar(255),
    parent_id varchar(255),
    amount bigint NOT NULL,
    method varchar(40),
    status varchar(24) NOT NULL,
    file_id varchar(255),
    issued_by varchar(255) NOT NULL,
    issued_at timestamptz NOT NULL,
    generated_at timestamptz,
    generation_attempts integer NOT NULL DEFAULT 0,
    generation_error varchar(500)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_receipt_number
    ON payment_receipts (receipt_number);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_receipt_payment
    ON payment_receipts (payment_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_receipt_file
    ON payment_receipts (file_id) WHERE file_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payment_receipt_invoice
    ON payment_receipts (invoice_id);
CREATE INDEX IF NOT EXISTS idx_payment_receipt_student
    ON payment_receipts (student_id);
CREATE INDEX IF NOT EXISTS idx_payment_receipt_parent
    ON payment_receipts (parent_id);

ALTER TABLE payment_receipts DROP CONSTRAINT IF EXISTS fk_payment_receipt_payment;
ALTER TABLE payment_receipts
    ADD CONSTRAINT fk_payment_receipt_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT;
ALTER TABLE payment_receipts DROP CONSTRAINT IF EXISTS fk_payment_receipt_invoice;
ALTER TABLE payment_receipts
    ADD CONSTRAINT fk_payment_receipt_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE RESTRICT;
ALTER TABLE payment_receipts DROP CONSTRAINT IF EXISTS fk_payment_receipt_file;
ALTER TABLE payment_receipts
    ADD CONSTRAINT fk_payment_receipt_file FOREIGN KEY (file_id) REFERENCES stored_files(id) ON DELETE RESTRICT;
ALTER TABLE payment_receipts DROP CONSTRAINT IF EXISTS ck_payment_receipt_status;
ALTER TABLE payment_receipts
    ADD CONSTRAINT ck_payment_receipt_status CHECK (status IN ('PENDING', 'ISSUED', 'FAILED', 'VOID'));
ALTER TABLE payment_receipts DROP CONSTRAINT IF EXISTS ck_payment_receipt_amount;
ALTER TABLE payment_receipts
    ADD CONSTRAINT ck_payment_receipt_amount CHECK (amount > 0);
ALTER TABLE payment_receipts DROP CONSTRAINT IF EXISTS ck_payment_receipt_attempts;
ALTER TABLE payment_receipts
    ADD CONSTRAINT ck_payment_receipt_attempts CHECK (generation_attempts >= 0);

-- P4.2: two-step refund workflow. Completed refunds reduce invoice paid_amount;
-- the original successful payment and receipt remain immutable evidence.
CREATE TABLE IF NOT EXISTS payment_refunds (
    id varchar(255) PRIMARY KEY,
    refund_number varchar(80) NOT NULL,
    payment_id varchar(255) NOT NULL,
    invoice_id varchar(255) NOT NULL,
    invoice_code varchar(255),
    student_id varchar(255) NOT NULL,
    student_code varchar(255),
    student_name varchar(255),
    parent_id varchar(255),
    amount bigint NOT NULL,
    reason varchar(500) NOT NULL,
    status varchar(24) NOT NULL,
    requested_by varchar(255) NOT NULL,
    requested_at timestamptz NOT NULL,
    approved_by varchar(255),
    approved_at timestamptz,
    rejected_by varchar(255),
    rejected_at timestamptz,
    rejection_reason varchar(500),
    cancelled_by varchar(255),
    cancelled_at timestamptz,
    cancellation_reason varchar(500),
    refund_method varchar(40),
    refund_reference varchar(120),
    completed_at timestamptz,
    updated_at timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_refund_number ON payment_refunds (refund_number);
CREATE INDEX IF NOT EXISTS idx_payment_refund_payment ON payment_refunds (payment_id);
CREATE INDEX IF NOT EXISTS idx_payment_refund_invoice ON payment_refunds (invoice_id);
CREATE INDEX IF NOT EXISTS idx_payment_refund_student ON payment_refunds (student_id);
CREATE INDEX IF NOT EXISTS idx_payment_refund_parent ON payment_refunds (parent_id);
CREATE INDEX IF NOT EXISTS idx_payment_refund_status ON payment_refunds (status);

ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS fk_payment_refund_payment;
ALTER TABLE payment_refunds
    ADD CONSTRAINT fk_payment_refund_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT;
ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS fk_payment_refund_invoice;
ALTER TABLE payment_refunds
    ADD CONSTRAINT fk_payment_refund_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE RESTRICT;
ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS ck_payment_refund_amount;
ALTER TABLE payment_refunds ADD CONSTRAINT ck_payment_refund_amount CHECK (amount > 0);
ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS ck_payment_refund_status;
ALTER TABLE payment_refunds
    ADD CONSTRAINT ck_payment_refund_status CHECK (status IN ('REQUESTED', 'COMPLETED', 'REJECTED', 'CANCELLED'));
ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS ck_payment_refund_method;
ALTER TABLE payment_refunds
    ADD CONSTRAINT ck_payment_refund_method
    CHECK (refund_method IS NULL OR refund_method IN ('MB_BANK_TRANSFER', 'CASH', 'OTHER'));

-- P4.4: immutable balance snapshots and duplicate transfer-reference protection.
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS refund_type varchar(16);
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS payment_amount bigint;
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS refunded_amount_before bigint;
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS refunded_amount_after bigint;
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS invoice_paid_amount_before bigint;
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS invoice_paid_amount_after bigint;
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS invoice_status_before varchar(24);
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS invoice_status_after varchar(24);

-- Drop this before legacy backfills. A previous application start may already
-- have installed the NOT VALID constraint, which still checks updated rows.
ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS ck_payment_refund_independent_reviewer;

UPDATE payment_refunds r
SET payment_amount = p.amount
FROM payments p
WHERE r.payment_id = p.id AND r.payment_amount IS NULL;

WITH completed_snapshots AS (
    SELECT r.id,
           COALESCE(SUM(r.amount) OVER (
               PARTITION BY r.payment_id
               ORDER BY r.completed_at, r.id
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS amount_before,
           SUM(r.amount) OVER (
               PARTITION BY r.payment_id
               ORDER BY r.completed_at, r.id
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS amount_after
    FROM payment_refunds r
    WHERE r.status = 'COMPLETED'
)
UPDATE payment_refunds r
SET refunded_amount_before = COALESCE(r.refunded_amount_before, s.amount_before),
    refunded_amount_after = COALESCE(r.refunded_amount_after, s.amount_after),
    refund_type = COALESCE(r.refund_type,
        CASE WHEN s.amount_after >= r.payment_amount THEN 'FULL' ELSE 'PARTIAL' END)
FROM completed_snapshots s
WHERE r.id = s.id;

UPDATE payment_refunds
SET refund_type = COALESCE(refund_type, 'PARTIAL')
WHERE refund_type IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_refund_completed_reference
    ON payment_refunds (refund_method, lower(refund_reference))
    WHERE status = 'COMPLETED' AND refund_reference IS NOT NULL;
ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS ck_payment_refund_type;
ALTER TABLE payment_refunds
    ADD CONSTRAINT ck_payment_refund_type CHECK (refund_type IN ('PARTIAL', 'FULL'));
ALTER TABLE payment_refunds DROP CONSTRAINT IF EXISTS ck_payment_refund_snapshots;
ALTER TABLE payment_refunds
    ADD CONSTRAINT ck_payment_refund_snapshots CHECK (
        (payment_amount IS NULL OR payment_amount > 0)
        AND (refunded_amount_before IS NULL OR refunded_amount_before >= 0)
        AND (refunded_amount_after IS NULL OR refunded_amount_after >= 0)
        AND (invoice_paid_amount_before IS NULL OR invoice_paid_amount_before >= 0)
        AND (invoice_paid_amount_after IS NULL OR invoice_paid_amount_after >= 0)
        AND (refunded_amount_before IS NULL OR refunded_amount_after IS NULL
            OR refunded_amount_before <= refunded_amount_after)
    );
ALTER TABLE payment_refunds
    ADD CONSTRAINT ck_payment_refund_independent_reviewer CHECK (
        (status <> 'COMPLETED' OR (approved_by IS NOT NULL AND approved_by <> requested_by))
        AND (status <> 'REJECTED' OR (rejected_by IS NOT NULL AND rejected_by <> requested_by))
    ) NOT VALID;

-- Reconcile invoice balances again after the refund schema is available.
-- REVERSED payments only represent collected money when a completed refund
-- exists; P0 duplicate-cleanup reversals have no refund and must stay excluded.
WITH invoice_balances AS (
    SELECT invoice.id,
           LEAST(
               invoice.total_amount,
               GREATEST(
                   0,
                   COALESCE((
                       SELECT SUM(payment.amount)
                       FROM payments payment
                       WHERE payment.invoice_id = invoice.id
                         AND (
                             payment.status = 'SUCCESS'
                             OR (
                                 payment.status = 'REVERSED'
                                 AND EXISTS (
                                     SELECT 1
                                     FROM payment_refunds refund
                                     WHERE refund.payment_id = payment.id
                                       AND refund.status = 'COMPLETED'
                                 )
                             )
                         )
                   ), 0)
                   - COALESCE((
                       SELECT SUM(refund.amount)
                       FROM payment_refunds refund
                       WHERE refund.invoice_id = invoice.id
                         AND refund.status = 'COMPLETED'
                   ), 0)
               )
           ) AS paid_amount
    FROM invoices invoice
    WHERE invoice.status NOT IN ('CANCELLED', 'VOID')
)
UPDATE invoices invoice
SET paid_amount = balance.paid_amount,
    status = CASE
        WHEN balance.paid_amount >= invoice.total_amount THEN 'PAID'
        WHEN balance.paid_amount > 0 THEN 'PARTIAL'
        WHEN invoice.due_date IS NOT NULL AND invoice.due_date < CURRENT_DATE THEN 'OVERDUE'
        ELSE 'PENDING'
    END
FROM invoice_balances balance
WHERE invoice.id = balance.id;

-- P4.3: reconciliation snapshots are unique by date range, amount range and payment method.
CREATE TABLE IF NOT EXISTS payment_reconciliation_runs (
    id varchar(255) PRIMARY KEY,
    reconciliation_date date NOT NULL,
    status varchar(24) NOT NULL,
    payment_count integer NOT NULL DEFAULT 0,
    gross_amount bigint NOT NULL DEFAULT 0,
    refund_count integer NOT NULL DEFAULT 0,
    refund_amount bigint NOT NULL DEFAULT 0,
    net_amount bigint NOT NULL DEFAULT 0,
    discrepancy_count integer NOT NULL DEFAULT 0,
    run_by varchar(255) NOT NULL,
    run_at timestamptz NOT NULL,
    run_count integer NOT NULL DEFAULT 1
);

ALTER TABLE payment_reconciliation_runs ADD COLUMN IF NOT EXISTS from_date date;
ALTER TABLE payment_reconciliation_runs ADD COLUMN IF NOT EXISTS to_date date;
ALTER TABLE payment_reconciliation_runs ADD COLUMN IF NOT EXISTS min_amount bigint;
ALTER TABLE payment_reconciliation_runs ADD COLUMN IF NOT EXISTS max_amount bigint;
ALTER TABLE payment_reconciliation_runs ADD COLUMN IF NOT EXISTS payment_method varchar(40);
ALTER TABLE payment_reconciliation_runs ADD COLUMN IF NOT EXISTS scope_key varchar(255);
UPDATE payment_reconciliation_runs
SET from_date = COALESCE(from_date, reconciliation_date),
    to_date = COALESCE(to_date, reconciliation_date),
    scope_key = COALESCE(scope_key,
        reconciliation_date::text || '|' || reconciliation_date::text || '|ALL|*|*');
ALTER TABLE payment_reconciliation_runs ALTER COLUMN from_date SET NOT NULL;
ALTER TABLE payment_reconciliation_runs ALTER COLUMN to_date SET NOT NULL;
ALTER TABLE payment_reconciliation_runs ALTER COLUMN scope_key SET NOT NULL;
ALTER TABLE payment_reconciliation_runs DROP CONSTRAINT IF EXISTS uk_payment_reconciliation_date;
DROP INDEX IF EXISTS uk_payment_reconciliation_date;
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_reconciliation_scope
    ON payment_reconciliation_runs (scope_key);
ALTER TABLE payment_reconciliation_runs DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_status;
ALTER TABLE payment_reconciliation_runs
    ADD CONSTRAINT ck_payment_reconciliation_status CHECK (status IN ('BALANCED', 'DISCREPANCY'));
ALTER TABLE payment_reconciliation_runs DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_counts;
ALTER TABLE payment_reconciliation_runs
    ADD CONSTRAINT ck_payment_reconciliation_counts
    CHECK (payment_count >= 0 AND refund_count >= 0 AND discrepancy_count >= 0 AND run_count > 0);
ALTER TABLE payment_reconciliation_runs DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_amount_range;
ALTER TABLE payment_reconciliation_runs
    ADD CONSTRAINT ck_payment_reconciliation_amount_range
    CHECK ((min_amount IS NULL OR min_amount >= 0)
        AND (max_amount IS NULL OR max_amount >= 0)
        AND (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount));
ALTER TABLE payment_reconciliation_runs DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_method;
ALTER TABLE payment_reconciliation_runs
    ADD CONSTRAINT ck_payment_reconciliation_method
    CHECK (payment_method IS NULL OR payment_method IN ('VNPAY', 'MOMO', 'CASH', 'MB_BANK_TRANSFER'));

CREATE TABLE IF NOT EXISTS payment_reconciliation_method_summaries (
    id varchar(255) PRIMARY KEY,
    run_id varchar(255) NOT NULL,
    method varchar(40) NOT NULL,
    payment_count integer NOT NULL DEFAULT 0,
    gross_amount bigint NOT NULL DEFAULT 0,
    refund_count integer NOT NULL DEFAULT 0,
    refund_amount bigint NOT NULL DEFAULT 0,
    net_amount bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_reconciliation_method
    ON payment_reconciliation_method_summaries (run_id, method);
CREATE INDEX IF NOT EXISTS idx_payment_reconciliation_method_run
    ON payment_reconciliation_method_summaries (run_id);
ALTER TABLE payment_reconciliation_method_summaries
    DROP CONSTRAINT IF EXISTS fk_payment_reconciliation_method_run;
ALTER TABLE payment_reconciliation_method_summaries
    ADD CONSTRAINT fk_payment_reconciliation_method_run
    FOREIGN KEY (run_id) REFERENCES payment_reconciliation_runs(id) ON DELETE CASCADE;
ALTER TABLE payment_reconciliation_method_summaries
    DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_method_counts;
ALTER TABLE payment_reconciliation_method_summaries
    ADD CONSTRAINT ck_payment_reconciliation_method_counts
    CHECK (payment_count >= 0 AND refund_count >= 0);

CREATE TABLE IF NOT EXISTS payment_reconciliation_issues (
    id varchar(255) PRIMARY KEY,
    run_id varchar(255) NOT NULL,
    issue_type varchar(60) NOT NULL,
    severity varchar(16) NOT NULL,
    entity_type varchar(40) NOT NULL,
    entity_id varchar(255) NOT NULL,
    expected_amount bigint,
    actual_amount bigint,
    message varchar(700) NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payment_reconciliation_issue_run
    ON payment_reconciliation_issues (run_id);
CREATE INDEX IF NOT EXISTS idx_payment_reconciliation_issue_entity
    ON payment_reconciliation_issues (entity_type, entity_id);
ALTER TABLE payment_reconciliation_issues DROP CONSTRAINT IF EXISTS fk_payment_reconciliation_issue_run;
ALTER TABLE payment_reconciliation_issues
    ADD CONSTRAINT fk_payment_reconciliation_issue_run
    FOREIGN KEY (run_id) REFERENCES payment_reconciliation_runs(id) ON DELETE CASCADE;
ALTER TABLE payment_reconciliation_issues DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_issue_severity;
ALTER TABLE payment_reconciliation_issues
    ADD CONSTRAINT ck_payment_reconciliation_issue_severity CHECK (severity IN ('ERROR', 'WARNING'));

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS last_reminder_at timestamptz;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS reminder_count integer DEFAULT 0;

ALTER TABLE payment_receipts ADD COLUMN IF NOT EXISTS revision integer DEFAULT 1;
ALTER TABLE payment_receipts ADD COLUMN IF NOT EXISTS previous_file_id varchar(255);
ALTER TABLE payment_receipts ADD COLUMN IF NOT EXISTS voided_by varchar(255);
ALTER TABLE payment_receipts ADD COLUMN IF NOT EXISTS voided_at timestamptz;
ALTER TABLE payment_receipts ADD COLUMN IF NOT EXISTS void_reason varchar(500);

CREATE TABLE IF NOT EXISTS bank_statement_entries (
    id varchar(255) PRIMARY KEY,
    bank_code varchar(32) NOT NULL,
    transaction_reference varchar(255) NOT NULL,
    amount bigint NOT NULL,
    transferred_at timestamptz NOT NULL,
    transfer_content varchar(1000),
    status varchar(24) NOT NULL,
    matched_invoice_id varchar(255),
    matched_payment_id varchar(255),
    mismatch_reason varchar(500),
    import_batch_id varchar(255) NOT NULL,
    imported_by varchar(255) NOT NULL,
    imported_at timestamptz NOT NULL,
    CONSTRAINT uk_bank_statement_txn
        UNIQUE(bank_code, transaction_reference)
);
CREATE INDEX IF NOT EXISTS idx_bank_statement_status
    ON bank_statement_entries(status);
CREATE INDEX IF NOT EXISTS idx_bank_statement_time
    ON bank_statement_entries(transferred_at);
