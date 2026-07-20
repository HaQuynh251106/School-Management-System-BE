BEGIN;

DELETE FROM notification_delivery_logs
WHERE notification_id IN (
    SELECT id FROM notifications
    WHERE ref_id IN (SELECT id FROM invoices WHERE code LIKE 'TEST-TC%')
       OR ref_id IN (
           SELECT id FROM payments
           WHERE invoice_id IN (SELECT id FROM invoices WHERE code LIKE 'TEST-TC%')
       )
);

DELETE FROM notifications
WHERE ref_id IN (SELECT id FROM invoices WHERE code LIKE 'TEST-TC%')
   OR ref_id IN (
       SELECT id FROM payments
       WHERE invoice_id IN (SELECT id FROM invoices WHERE code LIKE 'TEST-TC%')
   );

DELETE FROM payment_gateway_transactions
WHERE payment_id IN (
    SELECT id FROM payments
    WHERE invoice_id IN (SELECT id FROM invoices WHERE code LIKE 'TEST-TC%')
);

DELETE FROM payments
WHERE invoice_id IN (SELECT id FROM invoices WHERE code LIKE 'TEST-TC%');

DELETE FROM invoice_items
WHERE invoice_id IN (SELECT id FROM invoices WHERE code LIKE 'TEST-TC%');

DELETE FROM invoices WHERE code LIKE 'TEST-TC%';
DELETE FROM fee_period_items WHERE fee_period_id LIKE 'fp-test-tc-%';
DELETE FROM fee_periods WHERE code LIKE 'TEST-TC%';

COMMIT;
