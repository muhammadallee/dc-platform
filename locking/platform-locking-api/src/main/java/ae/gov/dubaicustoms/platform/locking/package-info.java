/**
 * The locking capability contract: {@link ae.gov.dubaicustoms.platform.locking.LockManager} runs an
 * action under a named, non-reentrant, auto-expiring distributed lock, failing with
 * {@link ae.gov.dubaicustoms.platform.locking.LockException} only on infrastructural errors.
 *
 * <p>Applications depend only on this module; {@code platform-locking-spi} defines the provider
 * contract, and {@code platform-locking-autoconfigure} supplies the implementation over whichever
 * {@code LockProvider} is on the classpath (JDBC by default, Redis when present).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.locking;
