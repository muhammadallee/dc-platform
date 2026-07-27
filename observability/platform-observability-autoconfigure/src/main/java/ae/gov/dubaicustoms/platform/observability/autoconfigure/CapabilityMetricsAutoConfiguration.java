package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.observability.autoconfigure.internal.CapabilityGaugeRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: micrometer MeterRegistry on the classpath AND a MeterRegistry bean exists AND
 *                 dc.platform.observability.enabled != false AND
 *                 dc.platform.observability.capability-metrics.enabled != false
 * Backs off when: user defines a CapabilityGaugeRegistrar bean
 * Beans: platformCapabilityGaugeRegistrar — emits platform.capability.active{capability=...} (0/1)
 *        from the CapabilityDescriptor beans once all singletons exist (adoption telemetry, F.1)
 * Order: none — the registrar is a SmartInitializingSingleton, so it runs after every capability
 *        has contributed its descriptor regardless of configuration order.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "dc.platform.observability", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class CapabilityMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dc.platform.observability.capability-metrics", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    CapabilityGaugeRegistrar platformCapabilityGaugeRegistrar(
            MeterRegistry registry, ObjectProvider<CapabilityDescriptor> descriptors) {
        return new CapabilityGaugeRegistrar(registry, descriptors);
    }
}
