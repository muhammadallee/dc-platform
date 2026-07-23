package ae.gov.dubaicustoms.platform.storage.autoconfigure;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.autoconfigure.internal.StorageDecorators;
import ae.gov.dubaicustoms.platform.storage.s3.S3ObjectStore;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

/*
 * Activates when: the AWS SDK S3 client and the S3 provider are on the classpath, an S3Client bean
 *                 exists, and dc.platform.storage.enabled != false.
 * Backs off when: an ObjectStore is already defined (a user bean, or nothing — the fs provider is
 *                 @AutoConfigureAfter this, so S3 wins whenever an S3Client is present).
 * Beans: s3ObjectStore — the S3 ObjectStore, decorated with checksum-on-put and observation.
 */
@AutoConfiguration
@ConditionalOnClass({S3Client.class, S3ObjectStore.class})
@ConditionalOnProperty(prefix = "dc.platform.storage", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StorageProperties.class)
public class S3ObjectStoreAutoConfiguration {

    @Bean
    @ConditionalOnBean(S3Client.class)
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore s3ObjectStore(S3Client client, StorageProperties properties,
                              ObjectProvider<ObservationRegistry> observationRegistry) {
        return StorageDecorators.decorate(new S3ObjectStore(client),
                properties.checksum().enabled(), observationRegistry.getIfAvailable());
    }
}
