/**
 * The default rate-limit provider: {@link
 * ae.gov.dubaicustoms.platform.ratelimit.inmemory.InMemoryRateLimiterProvider} is a per-JVM
 * sliding-window counter backed by Caffeine.
 *
 * <p>Selected automatically by the ratelimit autoconfigure when the Redis provider is absent. Counters
 * are not shared across instances — a documented limitation the autoconfigure warns about in prod.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.ratelimit.inmemory;
