ALTER TABLE users
    ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;

ALTER TABLE users
    ADD COLUMN token_version integer NOT NULL DEFAULT 0;

ALTER TABLE refresh_tokens
    ADD COLUMN ip_address varchar(255);

ALTER TABLE refresh_tokens
    ADD COLUMN user_agent varchar(1000);

ALTER TABLE classes
    ADD COLUMN capacity integer NOT NULL DEFAULT 45;

ALTER TABLE invoices
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_fee_period_code
    ON fee_periods (code);

CREATE UNIQUE INDEX uq_invoice_period_student
    ON invoices (fee_period_id, student_id);

CREATE TABLE payment_gateway_transactions (
    signature_valid boolean,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    callback_payload varchar(4000),
    gateway varchar(32) NOT NULL,
    id varchar(255) NOT NULL,
    payment_id varchar(255) NOT NULL,
    request_payload varchar(4000),
    status varchar(32) NOT NULL,
    txn_ref varchar(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (payment_id),
    UNIQUE (txn_ref)
);
CREATE INDEX idx_gateway_txn_status ON payment_gateway_transactions (status, created_at);

CREATE TABLE user_devices (
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    device_token varchar(1000) NOT NULL,
    id varchar(255) NOT NULL,
    platform varchar(32) NOT NULL,
    user_id varchar(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (device_token)
);
CREATE INDEX idx_user_device_user ON user_devices (user_id, active);
