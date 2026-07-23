package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure;

import ae.gov.dubaicustoms.platform.ratelimit.inmemory.InMemoryRateLimiterProvider;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the in-memory provider is on the classpath and dc.platform.ratelimit.enabled != false.
 * Backs off when: a RateLimiterProvider is already defined — which includes the Redis provider, since
 *                 this config is @AutoConfigureAfter RedisRateLimiterAutoConfiguration. So the in-memory
 *                 provider is the default fallback when Redis is absent.
 * Beans: inMemoryRateLimiterProvider — the per-JVM sliding-window provider.
 */
@AutoConfiguration
@AutoConfigureAfter(RedisRateLimiterAutoConfiguration.class)
@ConditionalOnClass(InMemoryRateLimiterProvider.class)
@ConditionalOnProperty(prefix = "dc.platform.ratelimit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InMemoryRateLimiterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RateLimiterProvider.class)
    RateLimiterProvider inMemoryRateLimiterProvider() {
        return new InMemoryRateLimiterProvider();
    }
}
