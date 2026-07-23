package ae.gov.dubaicustoms.platform.ratelimit.spi;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import java.time.Duration;

/**
 * The pluggable backend behind {@link ae.gov.dubaicustoms.platform.ratelimit.RateLimiter RateLimiter}.
 * Each provider module supplies one implementation: an in-memory sliding-window counter (per-JVM, the
 * default) or a Redis fixed-window counter (cluster-wide).
 *
 * <p>Called on the request path, so implementations must be fast and thread-safe. A provider that
 * cannot reach its backing store should fail open (return {@link Decision#allowed()}) rather than
 * reject traffic — availability over strict enforcement.
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface RateLimiterProvider {

    /**
     * Attempts to consume one permit for {@code key} within a {@code window}-length window sized at
     * {@code permits} permits.
     *
     * @param key the bucket key; never {@code null}
     * @param permits the maximum permits allowed within the window; positive
     * @param window the window length; never {@code null}
     * @return the decision (allowed, or denied with a retry-after hint)
     */
    Decision tryAcquire(String key, int permits, Duration window);
}
