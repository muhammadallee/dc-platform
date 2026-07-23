package ae.gov.dubaicustoms.platform.storage.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.autoconfigure.internal.StorageDecorators;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: an ObjectStore bean exists (contributed by the S3 or filesystem provider config, or
 *                 by the user) AND dc.platform.storage.enabled != false.
 * Backs off when: nothing to back off from — this config only reports the active provider; the
 *                 ObjectStore itself is owned by the provider configs (which honour user beans).
 * Beans: storageCapabilityDescriptor — one line in the startup capability banner naming the provider.
 * Order: after both provider auto-configurations so the ObjectStore is registered before the
 *        @ConditionalOnBean here is evaluated.
 */
@AutoConfiguration(after = {S3ObjectStoreAutoConfiguration.class, FsObjectStoreAutoConfiguration.class})
@ConditionalOnProperty(prefix = "dc.platform.storage", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StorageProperties.class)
public class PlatformStorageAutoConfiguration {

    @Bean
    @ConditionalOnBean(ObjectStore.class)
    CapabilityDescriptor storageCapabilityDescriptor(ObjectStore store) {
        return new CapabilityDescriptor("storage", "ACTIVE", StorageDecorators.providerName(store));
    }
}
