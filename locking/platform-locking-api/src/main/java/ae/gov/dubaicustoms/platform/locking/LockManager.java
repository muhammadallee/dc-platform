package ae.gov.dubaicustoms.platform.locking;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Distributed lock manager: runs an action while holding a named, cluster-wide lock so that at most
 * one instance across the deployment executes it at a time.
 *
 * <p><strong>Semantics.</strong> Locks are <strong>non-reentrant</strong> (a thread already holding
 * {@code name} will not re-acquire it) and <strong>auto-expiring</strong> (the lock is released
 * automatically after {@code atMost} even if the holder dies, so a crashed node never wedges the
 * cluster). Acquisition is <strong>non-blocking</strong>: if the lock is already held, the call
 * returns {@link Optional#empty()} immediately rather than waiting.
 *
 * <pre>{@code
 * lockManager.withLock("nightly-reconcile", Duration.ofMinutes(5), () -> {
 *     reconcile();
 *     return null;
 * });
 * }</pre>
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public interface LockManager {

    /**
     * Tries to acquire the lock named {@code name} and, if acquired, runs {@code action} while
     * holding it; if the lock is already held elsewhere, skips the action and returns
     * {@link Optional#empty()}.
     *
     * @param <T> the result type of the action
     * @param name the lock name, shared cluster-wide; never {@code null}
     * @param atMost the maximum time the lock is held before it auto-expires; never {@code null}
     * @param action the work to run under the lock; never {@code null}
     * @return the action's result wrapped in an {@link Optional} (empty if the action returned
     *     {@code null}), or {@link Optional#empty()} if the lock could not be acquired
     * @throws LockException if the lock backend fails, or if {@code action} throws a checked
     *     exception (wrapped)
     */
    <T> Optional<T> withLock(String name, Duration atMost, Callable<T> action) throws LockException;
}
