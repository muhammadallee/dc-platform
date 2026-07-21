package ae.gov.dubaicustoms.platform.locking.redis.internal;

import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Releases a Redis lock by running the token-fenced delete script. Exists to keep
 * {@link LockHandle#close()} idempotent and non-throwing: a failed release is logged, not propagated,
 * because the {@code PX} lease expires the key regardless.
 */
public final class RedisLockHandle implements LockHandle {

    private static final Logger LOG = LoggerFactory.getLogger(RedisLockHandle.class);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;
    private final String key;
    private final String token;
    private final AtomicBoolean released = new AtomicBoolean(false);

    /**
     * @param redis the template to run the release against
     * @param releaseScript the compare-and-delete Lua script
     * @param key the lock key
     * @param token the acquisition token that fences the release
     */
    public RedisLockHandle(StringRedisTemplate redis, RedisScript<Long> releaseScript,
            String key, String token) {
        this.redis = redis;
        this.releaseScript = releaseScript;
        this.key = key;
        this.token = token;
    }

    @Override
    public void close() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            redis.execute(releaseScript, List.of(key), token);
        } catch (RuntimeException e) {
            LOG.warn("Failed to release lock '{}'; it will auto-expire", key, e);
        }
    }
}
