/**
 * The rate-limiting provider contract: {@link
 * ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider} is the pluggable backend a {@code
 * RateLimiter} delegates to.
 *
 * <p>Provider modules implement it (in-memory sliding window, Redis fixed window); the ratelimit
 * autoconfigure selects one and wraps it in the {@code RateLimiter}. Applications never depend on this
 * module directly.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.ratelimit.spi;
