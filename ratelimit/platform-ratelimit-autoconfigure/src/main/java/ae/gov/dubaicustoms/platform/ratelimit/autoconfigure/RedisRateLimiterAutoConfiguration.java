package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure;

import ae.gov.dubaicustoms.platform.ratelimit.redis.RedisRateLimiterProvider;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * Activates when: the Redis provider and a StringRedisTemplate are on the classpath, a
 *                 StringRedisTemplate bean exists, and dc.platform.ratelimit.enabled != false.
 * Backs off when: a RateLimiterProvider is already defined (the user's, or none — Redis is preferred).
 * Beans: redisRateLimiterProvider — the cluster-wide fixed-window provider over Redis.
 * Order: the in-memory provider config is @AutoConfigureAfter this, so Redis wins when present.
 */
@AutoConfiguration
@ConditionalOnClass({RedisRateLimiterProvider.class, StringRedisTemplate.class})
@ConditionalOnProperty(prefix = "dc.platform.ratelimit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisRateLimiterAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(RateLimiterProvider.class)
    RateLimiterProvider redisRateLimiterProvider(StringRedisTemplate redis) {
        return new RedisRateLimiterProvider(redis);
    }
}
