package ae.gov.dubaicustoms.platform.flags.autoconfigure;

import ae.gov.dubaicustoms.platform.flags.inmemory.InMemoryFlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the in-memory provider is on the classpath and dc.platform.flags.enabled != false.
 * Backs off when: a FlagProvider is already defined — which includes the OpenFeature provider, since
 *                 this config is @AutoConfigureAfter OpenFeatureFlagProviderAutoConfiguration. So the
 *                 in-memory provider is the default fallback when OpenFeature is absent.
 * Beans: inMemoryFlagProvider — flags seeded from dc.platform.flags.static.*, mutable at runtime and
 *                 exposed through the platformflags actuator endpoint.
 */
@AutoConfiguration
@AutoConfigureAfter(OpenFeatureFlagProviderAutoConfiguration.class)
@ConditionalOnClass(InMemoryFlagProvider.class)
@ConditionalOnProperty(prefix = "dc.platform.flags", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlagsProperties.class)
public class InMemoryFlagProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FlagProvider.class)
    InMemoryFlagProvider inMemoryFlagProvider(FlagsProperties properties) {
        return new InMemoryFlagProvider(properties.staticFlags());
    }
}
