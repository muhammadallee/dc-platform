package ae.gov.dubaicustoms.platform.storage.autoconfigure;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.autoconfigure.internal.StorageDecorators;
import ae.gov.dubaicustoms.platform.storage.fs.FsObjectStore;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the filesystem provider is on the classpath and dc.platform.storage.enabled != false.
 * Backs off when: an ObjectStore is already defined — which includes the S3 provider, since this config
 *                 is @AutoConfigureAfter S3ObjectStoreAutoConfiguration. So filesystem is the default
 *                 fallback when S3 is absent.
 * Beans: fsObjectStore — the filesystem ObjectStore rooted at dc.platform.storage.fs.root, decorated
 *                 with checksum-on-put and observation.
 */
@AutoConfiguration
@AutoConfigureAfter(S3ObjectStoreAutoConfiguration.class)
@ConditionalOnClass(FsObjectStore.class)
@ConditionalOnProperty(prefix = "dc.platform.storage", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StorageProperties.class)
public class FsObjectStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore fsObjectStore(StorageProperties properties,
                              ObjectProvider<ObservationRegistry> observationRegistry) {
        return StorageDecorators.decorate(new FsObjectStore(properties.fs().resolvedRoot()),
                properties.checksum().enabled(), observationRegistry.getIfAvailable());
    }
}
