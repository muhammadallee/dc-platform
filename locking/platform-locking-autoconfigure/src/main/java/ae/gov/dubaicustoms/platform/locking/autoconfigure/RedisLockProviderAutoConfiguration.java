package ae.gov.dubaicustoms.platform.locking.autoconfigure;

import ae.gov.dubaicustoms.platform.locking.redis.RedisLockProvider;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * Activates when: Spring Data Redis (StringRedisTemplate) is on the classpath, a StringRedisTemplate
 *                 bean exists, and dc.platform.locking.enabled != false.
 * Backs off when: a LockProvider is already defined (user bean or, per ordering, nothing else yet).
 * Beans: redisLockProvider — the Redis LockProvider (SET NX PX + token-fenced release).
 * Order: this is the PREFERRED provider — JdbcLockProviderAutoConfiguration is @AutoConfigureAfter it,
 *        so when both a Redis template and a DataSource are present, Redis wins the
 *        @ConditionalOnMissingBean(LockProvider) race.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "dc.platform.locking", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisLockProviderAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(LockProvider.class)
    LockProvider redisLockProvider(StringRedisTemplate redis) {
        return new RedisLockProvider(redis);
    }
}
