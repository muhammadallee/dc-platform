/**
 * The rate-limiting capability contract: {@link ae.gov.dubaicustoms.platform.ratelimit.RateLimiter}
 * decides whether a keyed request is within its limit, {@link
 * ae.gov.dubaicustoms.platform.ratelimit.RateLimited} rate-limits a method declaratively, and {@link
 * ae.gov.dubaicustoms.platform.ratelimit.Decision} carries the outcome plus a retry hint.
 *
 * <p>Applications depend only on this module; {@code platform-ratelimit-spi} defines the provider
 * contract, and {@code platform-ratelimit-autoconfigure} supplies the {@code RateLimiter} over
 * whichever provider is on the classpath (in-memory by default, Redis when present).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.ratelimit;
