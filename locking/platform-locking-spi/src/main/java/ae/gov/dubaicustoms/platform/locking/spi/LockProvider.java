package ae.gov.dubaicustoms.platform.locking.spi;

import java.time.Duration;
import java.util.Optional;

/**
 * Pluggable backend for {@link ae.gov.dubaicustoms.platform.locking.LockManager}: acquires a named,
 * auto-expiring lock against a shared store (a database row, a Redis key, …).
 *
 * <p><strong>Implementation requirements.</strong>
 * <ul>
 *   <li><strong>Non-blocking:</strong> {@code tryAcquire} attempts the lock once and returns
 *       immediately — {@link Optional#empty()} if it is already held — rather than waiting.</li>
 *   <li><strong>Auto-expiry:</strong> the lock must expire on its own after {@code atMost} so a
 *       holder that dies without releasing never wedges the cluster. Enforce expiry in the store
 *       (e.g. an expiry column checked on acquire, or Redis {@code PX}), not with an in-JVM timer.</li>
 *   <li><strong>Safe release:</strong> the returned {@link LockHandle} must release only the lock it
 *       acquired — never one re-acquired by another holder after this one's expiry (fence with a
 *       per-acquisition token).</li>
 *   <li><strong>Non-reentrant:</strong> a second {@code tryAcquire} of a name already held returns
 *       empty; providers do not track ownership per thread.</li>
 *   <li><strong>Thread-safe:</strong> a single provider instance is shared platform-wide.</li>
 * </ul>
 *
 * @since 0.2.0
 */
public interface LockProvider {

    /**
     * Tries once to acquire the lock named {@code name}, returning a handle if acquired or
     * {@link Optional#empty()} if it is already held elsewhere.
     *
     * @param name the lock name, shared cluster-wide; never {@code null}
     * @param atMost the lease after which the lock auto-expires; never {@code null}
     * @return a {@link LockHandle} to release the lock, or {@link Optional#empty()} if not acquired
     */
    Optional<LockHandle> tryAcquire(String name, Duration atMost);
}
