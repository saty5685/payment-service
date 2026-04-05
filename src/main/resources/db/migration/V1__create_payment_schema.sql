-- ─────────────────────────────────────────────────────────────────────────────
-- V1__create_payment_schema.sql
-- Payment Service — initial schema
-- MySQL 8.0.13+
-- ─────────────────────────────────────────────────────────────────────────────

-- Tokenised payment method vault (PCI DSS: no raw PANs)
CREATE TABLE payment_methods (
    id                      VARCHAR(36)   NOT NULL,
    user_id                 VARCHAR(36)   NOT NULL,
    type                    VARCHAR(20)   NOT NULL,
    gateway                 VARCHAR(20)   NOT NULL,
    gateway_token           VARCHAR(200)  NOT NULL,

    -- Card display metadata (not CHD per PCI DSS)
    card_last4              VARCHAR(4),
    card_brand              VARCHAR(20),
    card_expiry_month       INT,
    card_expiry_year        INT,

    -- Bank account (encrypted)
    bank_account_encrypted  VARCHAR(500),
    ifsc_code               VARCHAR(11),
    account_holder_name     VARCHAR(100),

    -- UPI
    upi_vpa                 VARCHAR(100),

    -- State
    is_active               TINYINT(1)    NOT NULL DEFAULT 1,
    is_default              TINYINT(1)    NOT NULL DEFAULT 0,
    nickname                VARCHAR(50),
    created_at              DATETIME(6)   NOT NULL DEFAULT NOW(6),

    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_pm_user_id ON payment_methods (user_id);
CREATE INDEX idx_pm_active  ON payment_methods (user_id, is_active);


-- Payment attempts (top-ups, refunds)
CREATE TABLE payments (
    id                      VARCHAR(36)   NOT NULL,
    user_id                 VARCHAR(36)   NOT NULL,
    wallet_id               VARCHAR(36)   NOT NULL,
    type                    VARCHAR(20)   NOT NULL,
    status                  VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    amount                  DECIMAL(19,4) NOT NULL,
    currency                VARCHAR(3)    NOT NULL DEFAULT 'INR',
    payment_method_id       VARCHAR(36),
    gateway                 VARCHAR(20)   NOT NULL,
    gateway_charge_id       VARCHAR(100),
    gateway_event_id        VARCHAR(100),
    three_ds_redirect_url   VARCHAR(1000),
    refunded_amount         DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    failure_reason          VARCHAR(200),
    gateway_decline_code    VARCHAR(100),
    idempotency_key         VARCHAR(36)   NOT NULL,
    fraud_score             DECIMAL(5,4),
    created_at              DATETIME(6)   NOT NULL DEFAULT NOW(6),
    updated_at              DATETIME(6)   NOT NULL DEFAULT NOW(6) ON UPDATE NOW(6),
    captured_at             DATETIME(6),
    version                 BIGINT        NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_idempotency_key  (idempotency_key),
    UNIQUE KEY uk_pay_gateway_event_id (gateway_event_id)
    -- gateway_event_id UNIQUE: webhook dedup — second delivery is a no-op
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_pay_user_id          ON payments (user_id);
CREATE INDEX idx_pay_status           ON payments (status);
CREATE INDEX idx_pay_idempotency_key  ON payments (idempotency_key);
CREATE INDEX idx_pay_gateway_charge_id ON payments (gateway_charge_id);
CREATE INDEX idx_pay_created_at       ON payments (created_at);
-- Composite index for 3DS timeout job:
-- WHERE status = 'PENDING_3DS' AND created_at < NOW() - INTERVAL 15 MINUTE
CREATE INDEX idx_pay_pending_3ds      ON payments (status, created_at);


-- Bank withdrawal requests
CREATE TABLE withdrawal_requests (
    id                  VARCHAR(36)   NOT NULL,
    user_id             VARCHAR(36)   NOT NULL,
    wallet_id           VARCHAR(36)   NOT NULL,
    payment_method_id   VARCHAR(36)   NOT NULL,
    amount              DECIMAL(19,4) NOT NULL,
    currency            VARCHAR(3)    NOT NULL DEFAULT 'INR',
    status              VARCHAR(30)   NOT NULL DEFAULT 'WALLET_DEBIT_PENDING',
    neft_imps_reference VARCHAR(50),
    idempotency_key     VARCHAR(36)   NOT NULL,
    failure_reason      VARCHAR(200),
    created_at          DATETIME(6)   NOT NULL DEFAULT NOW(6),
    updated_at          DATETIME(6)   NOT NULL DEFAULT NOW(6) ON UPDATE NOW(6),
    completed_at        DATETIME(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_wr_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_wr_user_id    ON withdrawal_requests (user_id);
CREATE INDEX idx_wr_status     ON withdrawal_requests (status);
CREATE INDEX idx_wr_created_at ON withdrawal_requests (created_at);
