package ae.gov.dubaicustoms.example.extension;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.autoconfigure.FsObjectStoreAutoConfiguration;
import ae.gov.dubaicustoms.platform.storage.fs.FsObjectStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link EncryptingFsObjectStore} as the application's {@link ObjectStore}.
 *
 * <p>Activation conditions:
 * <ul>
 *   <li>ordered <b>before</b> {@link FsObjectStoreAutoConfiguration} — so this provider is defined
 *       first and the platform's filesystem default sees an {@code ObjectStore} already present and
 *       backs off (its bean is {@code @ConditionalOnMissingBean(ObjectStore.class)});</li>
 *   <li>{@link ObjectStore} on the classpath;</li>
 *   <li>kill switch {@code example.storage.encryption.enabled} (default on).</li>
 * </ul>
 *
 * <p>Back-off: if the application already defines an {@code ObjectStore} bean, this one yields via
 * {@code @ConditionalOnMissingBean}. This is the platform extension pattern verbatim — a provider is
 * added by registering it ahead of the default, never by editing the platform.
 */
@AutoConfiguration(before = FsObjectStoreAutoConfiguration.class)
@ConditionalOnClass(ObjectStore.class)
@ConditionalOnProperty(prefix = "example.storage.encryption", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(EncryptingStorageProperties.class)
public class EncryptingStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore encryptingObjectStore(EncryptingStorageProperties properties) {
        return new EncryptingFsObjectStore(
                new FsObjectStore(properties.resolvedRoot()),
                EncryptingFsObjectStore.keyFromBase64(properties.key()));
    }
}
