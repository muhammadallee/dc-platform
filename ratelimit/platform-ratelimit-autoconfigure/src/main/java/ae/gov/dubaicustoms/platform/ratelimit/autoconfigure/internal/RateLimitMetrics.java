package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

/**
 * Records rate-limit decisions, decoupling the aspect and filter from Micrometer. The default is a
 * no-op; when Micrometer is present a counting implementation is contributed instead. Keeping the
 * seam Micrometer-free means the always-on advisor and filter load without {@code micrometer-core} on
 * the classpath.
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface RateLimitMetrics {

    /** A metrics sink that records nothing. */
    RateLimitMetrics NOOP = (name, allowed) -> { };

    /**
     * Records one decision.
     *
     * @param name the bucket name
     * @param allowed whether the request was permitted
     */
    void record(String name, boolean allowed);
}
