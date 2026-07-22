package ae.gov.dubaicustoms.platform.idempotency.autoconfigure;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal.RedisIdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * Activates when: Spring Data Redis (StringRedisTemplate) is on the classpath, a StringRedisTemplate
 *                 bean exists, and dc.platform.idempotency.enabled != false.
 * Backs off when: an IdempotencyStore is already defined.
 * Beans: redisIdempotencyStore — the Redis-backed store (SET NX PX).
 * Order: PREFERRED over JDBC — JdbcIdempotencyStoreAutoConfiguration is @AutoConfigureAfter it, so
 *        Redis wins the @ConditionalOnMissingBean(IdempotencyStore) race when both are available.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "dc.platform.idempotency", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisIdempotencyStoreAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    IdempotencyStore redisIdempotencyStore(StringRedisTemplate redis) {
        return new RedisIdempotencyStore(redis);
    }
}
