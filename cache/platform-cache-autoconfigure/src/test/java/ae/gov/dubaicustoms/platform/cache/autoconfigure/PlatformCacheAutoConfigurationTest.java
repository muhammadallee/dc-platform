package ae.gov.dubaicustoms.platform.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.cache.CacheKeyConvention;
import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * The mandatory ContextRunner matrix for PlatformCacheAutoConfiguration. Redis is hidden from the
 * classpath so the Caffeine (default) provider is exercised; the Redis path is covered by the
 * {@code @Tag("docker")} round-trip IT.
 */
class PlatformCacheAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader(RedisConnectionFactory.class))
            .withConfiguration(AutoConfigurations.of(PlatformCacheAutoConfiguration.class));

    @Test
    void activeByDefaultWithCaffeine() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CacheKeyConvention.class);
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context).getBean(CacheManager.class).isInstanceOf(CaffeineCacheManager.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.cache.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CacheKeyConvention.class);
                    assertThat(context).doesNotHaveBean(CacheManager.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserCacheManagerPresent() {
        CacheManager mine = new NoOpCacheManager();
        runner.withBean("mine", CacheManager.class, () -> mine)
                .run(context -> assertThat(context.getBean(CacheManager.class)).isSameAs(mine));
    }

    @Test
    void backsOffWhenUserKeyConventionPresent() {
        CacheKeyConvention mine = (cacheName, parts) -> "mine";
        runner.withBean("mine", CacheKeyConvention.class, () -> mine)
                .run(context -> assertThat(context.getBean(CacheKeyConvention.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenCacheManagerClassMissing() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(CacheManager.class))
                .withConfiguration(AutoConfigurations.of(PlatformCacheAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformCacheAutoConfiguration.class));
    }

    @Test
    void keyConventionUsesApplicationName() {
        runner.withPropertyValues("spring.application.name=orders-service")
                .run(context -> assertThat(context.getBean(CacheKeyConvention.class).key("orders", 42))
                        .isEqualTo("orders-service:orders:42"));
    }
}
