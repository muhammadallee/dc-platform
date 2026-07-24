package ae.gov.dubaicustoms.example.extension;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the back-off half of the extension model: with both this module's
 * {@code EncryptingStorageAutoConfiguration} and the platform's {@code FsObjectStoreAutoConfiguration}
 * on the classpath, the application's single {@link ObjectStore} bean is the custom encrypting
 * provider — the platform default saw a bean already present and stepped aside.
 */
@SpringBootTest
class StorageBackOffTest {

    @Autowired
    private ObjectStore objectStore;

    @Test
    void customProviderWinsAndPlatformDefaultBacksOff() {
        assertThat(objectStore).isInstanceOf(EncryptingFsObjectStore.class);
    }
}
