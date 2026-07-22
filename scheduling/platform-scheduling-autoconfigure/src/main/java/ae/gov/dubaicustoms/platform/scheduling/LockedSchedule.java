package ae.gov.dubaicustoms.platform.scheduling;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Scheduled} method that must run on only one instance across the cluster at a time.
 * When the locking capability is active, the platform runs the method under
 * {@code LockManager.withLock(name, atMost, …)}: an instance that cannot acquire the lock skips that
 * firing. When no {@code LockManager} bean is present, the method runs unlocked (with a one-time
 * startup-log warning) so a service without a locking backend still functions in single-instance
 * deployments.
 *
 * <pre>{@code
 * @Scheduled(cron = "0 0 2 * * *")
 * @LockedSchedule(name = "nightly-reconcile", atMost = "5m")
 * public void reconcile() { ... }
 * }</pre>
 *
 * <p>Apply to public methods of Spring-managed beans; the lock is held only for the duration of the
 * method and is released automatically (including on failure) or by its {@code atMost} lease.
 *
 * @since 0.2.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LockedSchedule {

    /**
     * The lock name, shared cluster-wide; two methods sharing a name are mutually exclusive.
     *
     * @return the lock name
     */
    String name();

    /**
     * The maximum time the lock is held before it auto-expires, as a duration string (e.g.
     * {@code "5m"}, {@code "PT30S"}). Set it comfortably above the method's worst-case runtime so a
     * slow run is not pre-empted, but low enough that a crashed holder frees the lock promptly.
     *
     * @return the lock lease duration
     */
    String atMost() default "1h";
}
