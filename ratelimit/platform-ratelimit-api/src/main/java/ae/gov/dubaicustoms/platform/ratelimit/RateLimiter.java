package ae.gov.dubaicustoms.platform.ratelimit;

import java.time.Duration;

/**
 * Decides whether a keyed request is within its rate limit. Injected for programmatic checks; also
 * the engine behind {@link RateLimited} and the optional HTTP filter.
 *
 * <pre>{@code
 * if (!rateLimiter.tryAcquire("tenant:" + tenantId, 1000, Duration.ofMinutes(1)).allowed()) {
 *     throw new TooManyRequests();
 * }
 * }</pre>
 *
 * <p>Thread-safe; a single instance is shared platform-wide. Whether the counter is per-JVM or
 * cluster-wide depends on the active provider (in-memory vs Redis) — see the capability docs.
 *
 * @since 0.2.0
 */
public interface RateLimiter {

    /**
     * Attempts to consume one permit for {@code key} within a {@code window}-length window sized at
     * {@code permits} permits.
     *
     * @param key the bucket key (e.g. a user id, tenant, or IP); never {@code null}
     * @param permits the maximum number of permits allowed within the window; must be positive
     * @param window the window length; never {@code null}
     * @return an allowed {@link Decision} if within the limit, otherwise a denied one carrying a
     *     retry-after hint
     */
    Decision tryAcquire(String key, int permits, Duration window);
}
