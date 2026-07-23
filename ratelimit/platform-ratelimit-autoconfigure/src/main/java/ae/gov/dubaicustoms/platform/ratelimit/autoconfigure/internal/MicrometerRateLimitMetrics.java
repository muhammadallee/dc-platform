package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer-backed {@link RateLimitMetrics}: increments {@code dc.platform.ratelimit.decisions},
 * tagged {@code name} and {@code outcome=allowed|denied}. Contributed only when a {@code MeterRegistry}
 * is present.
 *
 * @since 0.2.0
 */
public final class MicrometerRateLimitMetrics implements RateLimitMetrics {

    private static final String METRIC = "dc.platform.ratelimit.decisions";

    private final MeterRegistry registry;

    /**
     * @param registry the meter registry counters are recorded to
     */
    public MicrometerRateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(String name, boolean allowed) {
        registry.counter(METRIC, "name", name, "outcome", allowed ? "allowed" : "denied").increment();
    }
}
