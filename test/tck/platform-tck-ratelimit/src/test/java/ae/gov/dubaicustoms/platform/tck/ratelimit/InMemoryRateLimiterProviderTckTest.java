package ae.gov.dubaicustoms.platform.tck.ratelimit;

import ae.gov.dubaicustoms.platform.ratelimit.inmemory.InMemoryRateLimiterProvider;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;

/**
 * Certifies the in-memory sliding-window provider against {@link RateLimiterProviderTck}, docker-free,
 * using the system clock so real window rollover can be awaited.
 */
class InMemoryRateLimiterProviderTckTest extends RateLimiterProviderTck {

    @Override
    protected RateLimiterProvider provider() {
        return new InMemoryRateLimiterProvider();
    }
}
