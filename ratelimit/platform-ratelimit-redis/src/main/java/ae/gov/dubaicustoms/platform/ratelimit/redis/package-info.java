/**
 * The Redis rate-limit provider: {@link
 * ae.gov.dubaicustoms.platform.ratelimit.redis.RedisRateLimiterProvider} maintains a cluster-wide
 * fixed-window counter via an atomic INCR + PEXPIRE Lua script.
 *
 * <p>Selected over the in-memory provider by the ratelimit autoconfigure when a
 * {@code StringRedisTemplate} is present. Fails open on Redis errors.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.ratelimit.redis;
