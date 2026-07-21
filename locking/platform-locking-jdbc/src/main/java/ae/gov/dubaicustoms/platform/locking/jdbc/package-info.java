/**
 * The default JDBC {@link ae.gov.dubaicustoms.platform.locking.spi.LockProvider}: a single
 * {@code platform_lock} table with token-fenced, expiry-column-driven auto-expiry. The Flyway
 * migration that creates the table ships under {@code db/migration-platform-locking}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.locking.jdbc;
