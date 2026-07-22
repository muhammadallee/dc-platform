/**
 * Auto-configuration for the scheduling capability: enables Spring scheduling on a virtual-thread
 * {@code TaskScheduler} and, when the locking capability is present, the advisor that runs
 * {@link ae.gov.dubaicustoms.platform.scheduling.LockedSchedule @LockedSchedule} methods under a lock.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.scheduling.autoconfigure;
