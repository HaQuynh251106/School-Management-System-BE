ALTER TABLE payment_gateway_transactions
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(120);

ALTER TABLE payment_gateway_transactions
    ADD COLUMN IF NOT EXISTS gateway_transaction_id VARCHAR(120);

ALTER TABLE payment_gateway_transactions
    ADD COLUMN IF NOT EXISTS callback_event_id VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS uq_gateway_txn_idempotency_key
    ON payment_gateway_transactions (idempotency_key);

CREATE UNIQUE INDEX IF NOT EXISTS uq_gateway_txn_gateway_transaction_id
    ON payment_gateway_transactions (gateway_transaction_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_gateway_txn_callback_event_id
    ON payment_gateway_transactions (callback_event_id);
