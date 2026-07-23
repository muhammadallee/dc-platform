package ae.gov.dubaicustoms.platform.ratelimit.redis;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RateLimiterProvider} backed by Redis: a <em>fixed-window</em> counter, so the limit is shared
 * across every instance of a service. One atomic Lua script per request increments the window's
 * counter and, on the first hit, sets the window's expiry — never a read-modify-write race between
 * nodes.
 *
 * <p>The window key carries a Redis Cluster hash tag ({@code {…}}) so the {@code INCR} and
 * {@code PEXPIRE} always target the same slot. The {@code retryAfter} hint is the key's remaining TTL.
 *
 * <p><strong>Fail-open:</strong> if Redis is unreachable the provider allows the request rather than
 * rejecting traffic — availability over strict enforcement (a rate limiter must not become a single
 * point of failure).
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public final class RedisRateLimiterProvider implements RateLimiterProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterProvider.class);

    // Atomic fixed-window counter: INCR the window key; on the first increment set its expiry to the
    // window length; return {count, remaining-ttl-millis}. Keeping both operations in one script makes
    // the count/expiry pair indivisible across concurrent nodes.
    private static final String LIMIT_LUA =
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "local ttl = redis.call('PTTL', KEYS[1]) "
                    + "return {current, ttl}";

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> limitScript;

    /**
     * @param redis the template addressing the Redis used for rate-limit counters
     */
    @SuppressWarnings("rawtypes")
    public RedisRateLimiterProvider(StringRedisTemplate redis) {
        this.redis = redis;
        this.limitScript = RedisScript.of(LIMIT_LUA, List.class);
    }

    @Override
    public Decision tryAcquire(String key, int permits, Duration window) {
        long windowMillis = Math.max(1, window.toMillis());
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(
                    limitScript, List.of("rl:{" + key + "}"), String.valueOf(windowMillis));
            long current = result.get(0);
            long ttlMillis = result.get(1);
            if (current > permits) {
                return Decision.deny(Duration.ofMillis(Math.max(0, ttlMillis)));
            }
            return Decision.allow();
        } catch (DataAccessException e) {
            // Fail open: never let a Redis outage turn the limiter into an availability incident.
            log.warn("[DC-RATELIMIT-0500] rate-limit check failed against Redis for key={}; allowing", key, e);
            return Decision.allow();
        }
    }
}
