package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.resilience.RetryableOperation;
import ae.gov.dubaicustoms.platform.resilience.autoconfigure.internal.DefaultRetryableOperation;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: Resilience4j's RetryRegistry is on the classpath (brought by the resilience starter)
 *                 AND dc.platform.resilience.enabled != false.
 * Backs off when: the user defines their own RetryableOperation bean.
 * Beans: retryableOperation — DefaultRetryableOperation over the RetryRegistry, so services get a
 *                 name-driven programmatic retry without depending on Resilience4j types. Requires a
 *                 RetryRegistry bean, which resilience4j-spring-boot4 contributes from the
 *                 resilience4j.* configuration (the platform's defaults + any user overrides);
 *        resilienceCapabilityDescriptor — one line in the startup capability banner.
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

    @Bean
    CapabilityDescriptor resilienceCapabilityDescriptor() {
        return new CapabilityDescriptor("resilience", "ACTIVE", "");
    }
}
