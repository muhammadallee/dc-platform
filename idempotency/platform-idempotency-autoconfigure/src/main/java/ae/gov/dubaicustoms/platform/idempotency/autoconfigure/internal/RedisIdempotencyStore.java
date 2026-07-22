package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link IdempotencyStore} backed by Redis: a single {@code SET key 1 NX PX ttl}, which is atomic and
 * self-expiring. Selected over the JDBC store when a {@link StringRedisTemplate} is present.
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redis;

    /**
     * @param redis the template addressing the Redis used for idempotency keys
     */
    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean putIfAbsent(String key, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
    }
}
