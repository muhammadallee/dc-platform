package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.observability.autoconfigure.internal.PlatformEndpoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: spring-boot-actuator on the classpath AND
 *                 dc.platform.observability.enabled != false
 * Backs off when: user defines a PlatformEndpoint bean
 * Beans: platformEndpoint — actuator @Endpoint(id="platform") returning the CapabilityDescriptor
 *                           list as JSON (only when the endpoint is exposed AND
 *                           dc.platform.observability.platform-endpoint.enabled != false);
 *        observabilityCapabilityDescriptor — reports observability[ACTIVE] into the banner with
 *                                            the metrics-export posture as detail
 * Order: none — the endpoint resolves descriptors lazily on each read, after every capability
 *        has contributed its bean.
 */
@AutoConfiguration
@ConditionalOnClass(Endpoint.class)
@ConditionalOnProperty(prefix = "dc.platform.observability", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class PlatformInfoEndpointAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = PlatformEndpoint.class)
    @ConditionalOnProperty(prefix = "dc.platform.observability.platform-endpoint", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    PlatformEndpoint platformEndpoint(ObjectProvider<CapabilityDescriptor> capabilities) {
        return new PlatformEndpoint(capabilities);
    }

    @Bean
    CapabilityDescriptor observabilityCapabilityDescriptor(ObservabilityProperties properties) {
        return new CapabilityDescriptor("observability", "ACTIVE",
                properties.otlp().enabled() ? "prometheus+otlp" : "prometheus");
    }
}
