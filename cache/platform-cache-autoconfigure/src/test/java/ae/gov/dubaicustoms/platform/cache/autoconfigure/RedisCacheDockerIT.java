package ae.gov.dubaicustoms.platform.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis provider round-trip against a real Redis. Excluded from the default build (docker JUnit
 * tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@Testcontainers
class RedisCacheDockerIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, PlatformCacheAutoConfiguration.class));

    @Test
    void redisCacheManagerRoundTrips() {
        runner.withPropertyValues(
                        "spring.data.redis.host=" + REDIS.getHost(),
                        "spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "dc.platform.cache.caches.orders.ttl=60s")
                .run(context -> {
                    CacheManager manager = context.getBean(CacheManager.class);
                    assertThat(manager).isInstanceOf(RedisCacheManager.class);

                    org.springframework.cache.Cache cache = manager.getCache("orders");
                    assertThat(cache).isNotNull();
                    cache.put("k", "v");
                    assertThat(cache.get("k", String.class)).isEqualTo("v");
                });
    }
}
