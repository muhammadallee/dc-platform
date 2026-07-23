package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.ratelimit.RateLimitExceededException;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimited;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimiter;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitExceptionAdvice;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitFilter;
import ae.gov.dubaicustoms.platform.ratelimit.inmemory.InMemoryRateLimiterProvider;
import ae.gov.dubaicustoms.platform.ratelimit.redis.RedisRateLimiterProvider;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** ContextRunner matrix plus provider selection, @RateLimited behavior, metrics, and web wiring. */
class PlatformRateLimitAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisRateLimiterAutoConfiguration.class,
                    InMemoryRateLimiterAutoConfiguration.class,
                    PlatformRateLimitAutoConfiguration.class));

    // --- mandatory ContextRunner matrix -------------------------------------------------------

    @Test
    void activeByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RateLimiter.class);
            assertThat(ctx.getBean(RateLimiterProvider.class)).isInstanceOf(InMemoryRateLimiterProvider.class);
            assertThat(ctx).hasBean("ratelimitCapabilityDescriptor");
            assertThat(ctx).hasBean("rateLimitedAdvisor");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.ratelimit.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RateLimiter.class);
            assertThat(ctx).doesNotHaveBean(RateLimiterProvider.class);
        });
    }

    @Test
    void backsOffWhenUserRateLimiterPresent() {
        RateLimiter mine = mock(RateLimiter.class);
        runner.withBean("mine", RateLimiter.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(RateLimiter.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenNoProviderOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(
                        InMemoryRateLimiterProvider.class, RedisRateLimiterProvider.class))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RateLimiterProvider.class);
                    assertThat(ctx).doesNotHaveBean(RateLimiter.class);
                });
    }

    @Test
    void redisProviderChosenOverInMemoryWhenTemplatePresent() {
        runner.withBean("redis", StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(ctx -> assertThat(ctx.getBean(RateLimiterProvider.class))
                        .isInstanceOf(RedisRateLimiterProvider.class));
    }

    // --- @RateLimited behavior ----------------------------------------------------------------

    @Test
    void rateLimitedMethodAllowsUpToPermitsThenRejects() {
        runner.withUserConfiguration(LimitedServiceConfiguration.class).run(ctx -> {
            LimitedService service = ctx.getBean(LimitedService.class);

            assertThat(service.op()).isEqualTo("ok");
            assertThat(service.op()).isEqualTo("ok");
            assertThatThrownBy(service::op).isInstanceOf(RateLimitExceededException.class);
        });
    }

    @Test
    void rateLimitedMethodKeysByExpressionSoDistinctKeysAreIndependent() {
        runner.withUserConfiguration(LimitedServiceConfiguration.class).run(ctx -> {
            LimitedService service = ctx.getBean(LimitedService.class);

            assertThat(service.forUser("alice")).isEqualTo("ok:alice");
            // alice is now at her limit of 1, but bob has his own bucket.
            assertThatThrownBy(() -> service.forUser("alice")).isInstanceOf(RateLimitExceededException.class);
            assertThat(service.forUser("bob")).isEqualTo("ok:bob");
        });
    }

    @Test
    void recordsDecisionMetricsWhenMeterRegistryPresent() {
        runner.withBean("meters", MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(LimitedServiceConfiguration.class)
                .run(ctx -> {
                    LimitedService service = ctx.getBean(LimitedService.class);
                    service.op();
                    service.op();

                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    double allowed = registry.get("dc.platform.ratelimit.decisions")
                            .tag("outcome", "allowed").counter().count();
                    assertThat(allowed).isEqualTo(2.0);
                });
    }

    // --- web wiring ---------------------------------------------------------------------------

    @Test
    void httpFilterAbsentByDefaultButPresentWhenEnabled() {
        WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisRateLimiterAutoConfiguration.class,
                        InMemoryRateLimiterAutoConfiguration.class,
                        PlatformRateLimitAutoConfiguration.class));

        webRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimitFilter.class));
        webRunner.withPropertyValues("dc.platform.ratelimit.http.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(RateLimitFilter.class));
    }

    @Test
    void exceptionAdviceRegisteredInWebApplication() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisRateLimiterAutoConfiguration.class,
                        InMemoryRateLimiterAutoConfiguration.class,
                        PlatformRateLimitAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(RateLimitExceptionAdvice.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class LimitedServiceConfiguration {
        @Bean
        LimitedService limitedService() {
            return new LimitedService();
        }
    }

    static class LimitedService {

        @RateLimited(name = "op", permits = 2, window = "PT1M")
        public String op() {
            return "ok";
        }

        @RateLimited(name = "byUser", permits = 1, window = "PT1M", keyExpression = "#user")
        public String forUser(String user) {
            return "ok:" + user;
        }
    }
}
