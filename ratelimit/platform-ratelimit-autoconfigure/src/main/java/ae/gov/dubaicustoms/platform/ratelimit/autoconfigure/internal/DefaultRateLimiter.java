package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimiter;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import java.time.Duration;

/**
 * The platform {@link RateLimiter}: a thin delegate over the selected {@link RateLimiterProvider}
 * (in-memory or Redis). Separating the facade from the provider keeps the provider modules free of the
 * application-facing type and lets the autoconfigure swap backends without touching callers.
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public final class DefaultRateLimiter implements RateLimiter {

    private final RateLimiterProvider provider;

    /**
     * @param provider the backend the decisions are delegated to
     */
    public DefaultRateLimiter(RateLimiterProvider provider) {
        this.provider = provider;
    }

    @Override
    public Decision tryAcquire(String key, int permits, Duration window) {
        return provider.tryAcquire(key, permits, window);
    }
}
