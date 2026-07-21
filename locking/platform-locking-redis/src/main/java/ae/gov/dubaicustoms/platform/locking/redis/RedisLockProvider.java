package ae.gov.dubaicustoms.platform.locking.redis;

import ae.gov.dubaicustoms.platform.locking.redis.internal.RedisLockHandle;
import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link LockProvider} backed by Redis. Acquisition is a single {@code SET key token NX PX atMost}:
 * atomic, non-blocking, and self-expiring via the {@code PX} lease, so a crashed holder's lock frees
 * itself. Release runs a compare-and-delete Lua script so a holder only ever deletes the value it
 * wrote — never a lock re-acquired by another node after this one's lease lapsed.
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public final class RedisLockProvider implements LockProvider {

    // Compare-and-delete: delete the key only if it still holds our token. Pattern from the Redis
    // distributed-lock docs (https://redis.io/docs/latest/develop/use/patterns/distributed-locks/).
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;

    /**
     * @param redis the template addressing the Redis used for locks
     */
    public RedisLockProvider(StringRedisTemplate redis) {
        this.redis = redis;
        this.releaseScript = RedisScript.of(RELEASE_LUA, Long.class);
    }

    @Override
    public Optional<LockHandle> tryAcquire(String name, Duration atMost) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(name, token, atMost);
        if (Boolean.TRUE.equals(acquired)) {
            return Optional.of(new RedisLockHandle(redis, releaseScript, name, token));
        }
        return Optional.empty();
    }
}
