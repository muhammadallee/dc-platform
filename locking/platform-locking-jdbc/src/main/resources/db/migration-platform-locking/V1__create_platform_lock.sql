-- Platform distributed-lock table. One row per held lock; auto-expiry is enforced by the
-- expires_at column (checked on acquire), and release is fenced by the per-acquisition token so a
-- holder can only release the lock it took. Portable DDL (H2 + PostgreSQL).
CREATE TABLE IF NOT EXISTS platform_lock (
    lock_name  VARCHAR(255) NOT NULL,
    token      VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_platform_lock PRIMARY KEY (lock_name)
);
