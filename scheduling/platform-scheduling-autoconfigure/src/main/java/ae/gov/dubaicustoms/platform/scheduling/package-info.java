/**
 * The scheduling capability: {@link ae.gov.dubaicustoms.platform.scheduling.LockedSchedule
 * @LockedSchedule} marks a {@code @Scheduled} method that must run on a single instance cluster-wide.
 * {@code platform-scheduling-autoconfigure} enables Spring scheduling on a virtual-thread scheduler
 * and wires the advisor that enforces the lock via the locking capability.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.scheduling;
