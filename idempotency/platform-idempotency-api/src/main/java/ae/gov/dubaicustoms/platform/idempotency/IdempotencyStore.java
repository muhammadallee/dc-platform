package ae.gov.dubaicustoms.platform.idempotency;

import java.time.Duration;
import org.apiguardian.api.API;

/**
 * Store of seen idempotency keys with per-key expiry — an SPI-lite kept in the api because it is small
 * and its single method carries no provider types. The platform ships a JDBC default and a Redis
 * implementation; applications may supply their own.
 *
 * <p><strong>Implementation requirements.</strong> {@link #putIfAbsent} must be atomic against
 * concurrent callers (only one may receive {@code true} for a given key while it is live), must expire
 * a key on its own after {@code ttl} (so a key seen 25 hours ago does not reject a fresh request), and
 * must be thread-safe. A single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public interface IdempotencyStore {

    /**
     * Records {@code key} if it is not already present, atomically.
     *
     * @param key the idempotency key; never {@code null}
     * @param ttl how long the key remains recorded before it expires; never {@code null}
     * @return {@code true} if the key was newly recorded (first occurrence — proceed), {@code false}
     *     if it was already present and still live (a duplicate — reject)
     */
    boolean putIfAbsent(String key, Duration ttl);
}
