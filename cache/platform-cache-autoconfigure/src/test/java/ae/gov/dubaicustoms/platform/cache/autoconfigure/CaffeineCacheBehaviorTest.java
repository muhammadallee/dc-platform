package ae.gov.dubaicustoms.platform.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** Proves the Caffeine manager applies the per-cache max-size policy from dc.platform.cache.caches.*. */
class CaffeineCacheBehaviorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader(RedisConnectionFactory.class))
            .withConfiguration(AutoConfigurations.of(PlatformCacheAutoConfiguration.class));

    @Test
    void configuredCacheIsPreRegistered() {
        runner.withPropertyValues("dc.platform.cache.caches.orders.max-size=100")
                .run(context -> assertThat(context.getBean(CacheManager.class).getCache("orders")).isNotNull());
    }

    @Test
    void maxSizeEvictsBeyondTheBound() {
        runner.withPropertyValues("dc.platform.cache.caches.tiny.max-size=1")
                .run(context -> {
                    org.springframework.cache.Cache springCache =
                            context.getBean(CacheManager.class).getCache("tiny");
                    assertThat(springCache).isNotNull();

                    @SuppressWarnings("unchecked")
                    Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();
                    springCache.put("a", "1");
                    springCache.put("b", "2");
                    springCache.put("c", "3");
                    nativeCache.cleanUp();

                    assertThat(nativeCache.estimatedSize()).isLessThanOrEqualTo(1);
                });
    }
}
