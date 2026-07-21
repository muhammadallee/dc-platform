/**
 * The locking provider contract: {@link ae.gov.dubaicustoms.platform.locking.spi.LockProvider}
 * acquires a named auto-expiring lock and returns a {@link ae.gov.dubaicustoms.platform.locking.spi.LockHandle}
 * that releases it. Implemented by {@code platform-locking-jdbc} and {@code platform-locking-redis};
 * applications never depend on this package.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.locking.spi;
