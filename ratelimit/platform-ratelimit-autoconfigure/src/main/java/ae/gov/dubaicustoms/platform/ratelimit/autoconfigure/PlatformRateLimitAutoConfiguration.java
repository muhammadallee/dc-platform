package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimiter;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.DefaultRateLimiter;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.MicrometerRateLimitMetrics;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitExceptionAdvice;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitFilter;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitMetrics;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitedAdvisorFactory;
import ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitedAutoProxyRegistrar;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpFilter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ProblemDetail;

/*
 * Activates when: a RateLimiterProvider bean exists (Redis or in-memory provider config, or user) AND
 *                 dc.platform.ratelimit.enabled != false.
 * Backs off when: the user defines their own RateLimiter / rateLimitedAdvisor / RateLimitMetrics.
 * Beans: rateLimiter — DefaultRateLimiter over the selected provider (WARNs in prod if per-JVM);
 *        noopRateLimitMetrics — the metrics fallback when Micrometer is absent;
 *        micrometerRateLimitMetrics — decision counters when a MeterRegistry is present;
 *        ratelimitCapabilityDescriptor — one line in the startup capability banner naming the provider;
 *        rateLimitedAdvisor — a plain AOP advisor enforcing @RateLimited;
 *        rateLimitFilter — (servlet app + http.enabled=true) an all-requests 429 filter;
 *        rateLimitExceptionAdvice — (Spring MVC) maps RateLimitExceededException to 429.
 * Order: after the provider auto-configurations so the RateLimiterProvider is registered first.
 */
@AutoConfiguration(after = {
        RedisRateLimiterAutoConfiguration.class, InMemoryRateLimiterAutoConfiguration.class})
@ConditionalOnProperty(prefix = "dc.platform.ratelimit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
public class PlatformRateLimitAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformRateLimitAutoConfiguration.class);

    @Bean
    @ConditionalOnBean(RateLimiterProvider.class)
    @ConditionalOnMissingBean
    RateLimiter rateLimiter(RateLimiterProvider provider, Environment environment) {
        if (isPerJvm(provider) && environment.acceptsProfiles(Profiles.of("prod"))) {
            log.warn("[DC-RATELIMIT-0100] in-memory rate limiter active in prod: counters are per-JVM, so "
                    + "the effective limit is multiplied by the instance count. Use the Redis provider for "
                    + "a cluster-wide limit.");
        }
        return new DefaultRateLimiter(provider);
    }

    @Bean
    @ConditionalOnBean(RateLimiterProvider.class)
    CapabilityDescriptor ratelimitCapabilityDescriptor(RateLimiterProvider provider) {
        return new CapabilityDescriptor("ratelimit", "ACTIVE", describe(provider));
    }

    // Name the provider by its simple class name (RedisRateLimiterProvider -> "redis").
    private static String describe(RateLimiterProvider provider) {
        return provider.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("ratelimiterprovider", "");
    }

    private static boolean isPerJvm(RateLimiterProvider provider) {
        return provider.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("inmemory");
    }

    /*
     * The @RateLimited advisor: a plain auto-proxy (decision D26). Gated on RateLimiterProvider (a
     * separate, earlier auto-config) rather than RateLimiter so @ConditionalOnBean sees a reliably
     * registered bean; injecting RateLimiter forces it to be created first.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(RateLimitedAutoProxyRegistrar.class)
    static class RateLimitedAdvisorConfiguration {

        // ROLE_INFRASTRUCTURE: the InfrastructureAdvisorAutoProxyCreator only considers Advisor beans
        // with this role — a plain application-role bean would be silently ignored (decision D26).
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnBean(RateLimiterProvider.class)
        @ConditionalOnMissingBean(name = "rateLimitedAdvisor")
        Advisor rateLimitedAdvisor(RateLimiter rateLimiter, ObjectProvider<RateLimitMetrics> metrics) {
            return RateLimitedAdvisorFactory.create(rateLimiter, metrics.getIfAvailable(() -> RateLimitMetrics.NOOP));
        }
    }

    /*
     * Decision metrics: a RateLimitMetrics bean is contributed only when Micrometer is on the classpath
     * and a MeterRegistry bean exists; otherwise consumers (advisor, filter) fall back to the no-op via
     * ObjectProvider, so no bean-ordering race decides which metrics implementation wins.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean(RateLimitMetrics.class)
        RateLimitMetrics micrometerRateLimitMetrics(MeterRegistry registry) {
            return new MicrometerRateLimitMetrics(registry);
        }
    }

    /*
     * The all-requests HTTP rate-limit filter: only in a servlet web app, only when explicitly enabled
     * (dc.platform.ratelimit.http.enabled=true). Keys by user or IP per the properties.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HttpFilter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "dc.platform.ratelimit.http", name = "enabled", havingValue = "true")
    static class HttpFilterConfiguration {

        // Gated on RateLimiterProvider (a separate, earlier auto-config) rather than RateLimiter, which
        // is declared in the same auto-config and so not reliably visible to @ConditionalOnBean here.
        @Bean
        @ConditionalOnBean(RateLimiterProvider.class)
        @ConditionalOnMissingBean
        RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, ObjectProvider<RateLimitMetrics> metrics,
                RateLimitProperties properties) {
            RateLimitProperties.Http http = properties.http();
            boolean keyByUser = http.keyBy() == RateLimitProperties.KeyBy.USER;
            return new RateLimitFilter(rateLimiter, metrics.getIfAvailable(() -> RateLimitMetrics.NOOP),
                    keyByUser, http.permits(), http.window());
        }
    }

    /*
     * The MVC exception advice: maps RateLimitExceededException (thrown by @RateLimited) to 429 with a
     * ProblemDetail body. Only in a Spring MVC servlet app.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ProblemDetail.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ExceptionAdviceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        RateLimitExceptionAdvice rateLimitExceptionAdvice() {
            return new RateLimitExceptionAdvice();
        }
    }
}
