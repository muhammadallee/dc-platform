package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: micrometer-core + spring-boot-micrometer-metrics on the classpath AND
 *                 dc.platform.observability.enabled != false AND
 *                 dc.platform.observability.common-tags.enabled != false
 * Backs off when: user defines a bean named platformCommonTagsCustomizer
 * Beans: platformCommonTagsCustomizer — stamps service (spring.application.name), env (first
 *        active profile) and platform.version (platform jar manifest) on every meter registry
 * Order: none — MeterRegistryCustomizer beans are collected by Boot's registry post-processor
 *        whenever a registry is created, regardless of configuration order.
 */
@AutoConfiguration
@ConditionalOnClass({MeterRegistry.class, MeterRegistryCustomizer.class})
@ConditionalOnProperty(prefix = "dc.platform.observability", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class CommonTagsAutoConfiguration {

    /** Tag value when the platform jar manifest is not readable (exploded classpaths, tests). */
    static final String UNKNOWN_VERSION = "unknown";

    @Bean
    @ConditionalOnMissingBean(name = "platformCommonTagsCustomizer")
    @ConditionalOnProperty(prefix = "dc.platform.observability.common-tags", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    MeterRegistryCustomizer<MeterRegistry> platformCommonTagsCustomizer(Environment environment) {
        String service = environment.getProperty("spring.application.name", "application");
        // First active profile only: dashboards slice by ONE environment dimension.
        String[] profiles = environment.getActiveProfiles();
        String env = profiles.length > 0 ? profiles[0] : "default";
        String platformVersion = platformVersion();
        return registry -> registry.config().commonTags(
                "service", service, "env", env, "platform.version", platformVersion);
    }

    /**
     * Reads the platform train version from the core-api jar manifest
     * ({@code Implementation-Version}); {@link #UNKNOWN_VERSION} on exploded classpaths where no
     * manifest is available.
     */
    private static String platformVersion() {
        String version = ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor.class
                .getPackage().getImplementationVersion();
        return version != null ? version : UNKNOWN_VERSION;
    }
}
