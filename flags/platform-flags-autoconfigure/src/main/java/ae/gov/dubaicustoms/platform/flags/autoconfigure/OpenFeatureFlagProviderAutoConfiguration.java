package ae.gov.dubaicustoms.platform.flags.autoconfigure;

import ae.gov.dubaicustoms.platform.flags.openfeature.OpenFeatureFlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import dev.openfeature.sdk.Client;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the OpenFeature SDK and adapter are on the classpath, an OpenFeature Client bean
 *                 exists, and dc.platform.flags.enabled != false.
 * Backs off when: a FlagProvider is already defined (a user bean). The in-memory provider is
 *                 @AutoConfigureAfter this, so OpenFeature wins whenever a Client is present.
 * Beans: openFeatureFlagProvider — the OpenFeature-backed FlagProvider.
 */
@AutoConfiguration
@ConditionalOnClass({Client.class, OpenFeatureFlagProvider.class})
@ConditionalOnProperty(prefix = "dc.platform.flags", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OpenFeatureFlagProviderAutoConfiguration {

    @Bean
    @ConditionalOnBean(Client.class)
    @ConditionalOnMissingBean(FlagProvider.class)
    FlagProvider openFeatureFlagProvider(Client client) {
        return new OpenFeatureFlagProvider(client);
    }
}
