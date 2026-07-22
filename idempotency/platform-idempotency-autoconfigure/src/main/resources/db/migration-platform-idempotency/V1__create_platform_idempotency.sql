-- Platform idempotency-key table. One row per remembered key; auto-expiry is enforced by the
-- expires_at column (checked on write), mirroring the locking table pattern. Portable DDL (H2 + PostgreSQL).
CREATE TABLE IF NOT EXISTS platform_idempotency (
    idem_key   VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_platform_idempotency PRIMARY KEY (idem_key)
);
