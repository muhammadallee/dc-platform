package ae.gov.dubaicustoms.platform.core.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: dc.platform.core.enabled != false
 * Backs off when: user defines a PlatformBannerRunner bean (by type); user defines a bean named
 *                 coreCapabilityDescriptor (by name — CapabilityDescriptor is a one-per-capability
 *                 type, overridden like Boot's health indicators, D93/D97)
 * Beans: coreCapabilityDescriptor — reports core[ACTIVE] into the banner (present even when the
 *                                   banner itself is switched off, for other report consumers);
 *        platformBannerRunner — logs one INFO line listing all active capabilities, sorted by
 *                               name (only when dc.platform.core.banner-enabled != false)
 * Order: none — the runner executes after the context is ready, when every capability has
 *        contributed its descriptor.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "dc.platform.core", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CoreProperties.class)
public class PlatformBannerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "coreCapabilityDescriptor")
    CapabilityDescriptor coreCapabilityDescriptor() {
        return new CapabilityDescriptor("core", "ACTIVE", "");
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dc.platform.core", name = "banner-enabled",
                           havingValue = "true", matchIfMissing = true)
    PlatformBannerRunner platformBannerRunner(ObjectProvider<CapabilityDescriptor> descriptors,
            Environment environment) {
        return new PlatformBannerRunner(descriptors, environment);
    }
}
