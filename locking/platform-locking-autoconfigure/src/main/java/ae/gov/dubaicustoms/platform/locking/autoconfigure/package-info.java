/**
 * Auto-configuration for the locking capability: selects a
 * {@link ae.gov.dubaicustoms.platform.locking.spi.LockProvider} (Redis when a {@code StringRedisTemplate}
 * is present, otherwise JDBC when a {@code DataSource} is present) and wires a
 * {@link ae.gov.dubaicustoms.platform.locking.LockManager} over it, both backing off to any user bean.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.locking.autoconfigure;
