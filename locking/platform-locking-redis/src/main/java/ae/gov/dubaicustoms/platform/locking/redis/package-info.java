/**
 * The Redis {@link ae.gov.dubaicustoms.platform.locking.spi.LockProvider}: {@code SET NX PX} to
 * acquire and a token-fenced compare-and-delete Lua script to release. Selected over the JDBC default
 * when Spring Data Redis is on the classpath and enabled.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.locking.redis;
