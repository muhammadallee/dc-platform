package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.resilience.RetryableOperation;
import ae.gov.dubaicustoms.platform.resilience.autoconfigure.internal.DefaultRetryableOperation;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: Resilience4j's RetryRegistry is on the classpath (brought by the resilience starter)
 *                 AND dc.platform.resilience.enabled != false.
 * Backs off when: the user defines their own RetryableOperation bean.
 * Beans: retryableOperation — DefaultRetryableOperation over the RetryRegistry, so services get a
 *                 name-driven programmatic retry without depending on Resilience4j types. Requires a
 *                 RetryRegistry bean, which resilience4j-spring-boot4 contributes from the
 *                 resilience4j.* configuration (the platform's defaults + any user overrides);
 *        resilienceCapabilityDescriptor — one line in the startup capability banner.
 *        threadPoolBulkheadGuard — only with virtual threads on: fails startup if a thread-pool bulkhead is configured.
 * Micrometer binding is left to resilience4j-spring-boot4 (metrics on by default when a MeterRegistry
 *                 is present); adding our own TaggedRetryMetrics binder would double-register meters.
 * Order: after Resilience4j's RetryAutoConfiguration (by name, since that module is only on the
 *                 runtime/starter classpath) so its RetryRegistry wins the @ConditionalOnBean check.
 */
@AutoConfiguration(
        afterName = "io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration")
@ConditionalOnClass(RetryRegistry.class)
@ConditionalOnProperty(prefix = "dc.platform.resilience", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ResilienceProperties.class)
public class PlatformResilienceAutoConfiguration {

    @Bean
    @ConditionalOnBean(RetryRegistry.class)
    @ConditionalOnMissingBean
    RetryableOperation retryableOperation(RetryRegistry retryRegistry) {
        return new DefaultRetryableOperation(retryRegistry);
    }

    /** A thread-pool bulkhead moves blocking calls onto platform threads, undoing virtual threads, so it stops startup. */
    @Bean
    @ConditionalOnProperty(name = "spring.threads.virtual.enabled", havingValue = "true")
    static SmartInitializingSingleton threadPoolBulkheadGuard(Environment environment) {
        List<ConfigurationPropertyName> names = Stream.of("instances", "backends", "configs")
                .map(group -> ConfigurationPropertyName.of("resilience4j.thread-pool-bulkhead." + group)).toList();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (names.stream().anyMatch(name -> source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT)) {
                throw new IllegalStateException("resilience4j.thread-pool-bulkhead is configured while "
                        + "spring.threads.virtual.enabled=true: a thread-pool bulkhead would pin blocking calls to platform "
                        + "threads. Use a semaphore bulkhead (resilience4j.bulkhead), or remove the thread-pool configuration");
            }
        }
        return () -> { };
    }

    @Bean
    CapabilityDescriptor resilienceCapabilityDescriptor() {
        return new CapabilityDescriptor("resilience", "ACTIVE", "");
    }
}
