package ae.gov.dubaicustoms.platform.locking.spi;

import org.apiguardian.api.API;

/**
 * A held distributed lock, returned by {@link LockProvider#tryAcquire}. Closing the handle releases
 * the lock; the manager runs it inside a try-with-resources so the lock is always released, even if
 * the guarded action throws.
 *
 * <p>Thread-safety: a handle is confined to the thread that acquired it; it is not shared.
 *
 * @since 0.2.0
 */
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public interface LockHandle extends AutoCloseable {

    /**
     * Releases the held lock. Must be idempotent (safe to call more than once) and must not throw —
     * a release failure is a backend concern the provider logs, not something the caller can act on;
     * releasing an already-expired lock is a no-op.
     */
    @Override
    void close();
}
