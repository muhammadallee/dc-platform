/**
 * Auto-configuration for the rate-limiting capability: selects the {@code RateLimiterProvider} (Redis
 * over in-memory), wraps it in the {@code RateLimiter}, enforces {@code @RateLimited} through a plain
 * AOP advisor, optionally installs an all-requests HTTP filter, maps rejections to HTTP 429, and
 * records decision metrics when Micrometer is present.
 *
 * <p>The provider configs
 * ({@link ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.RedisRateLimiterAutoConfiguration},
 * {@link ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.InMemoryRateLimiterAutoConfiguration})
 * contribute exactly one provider by priority;
 * {@link ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.PlatformRateLimitAutoConfiguration}
 * wires the rest. Every bean backs off on a user-supplied equivalent.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure;
