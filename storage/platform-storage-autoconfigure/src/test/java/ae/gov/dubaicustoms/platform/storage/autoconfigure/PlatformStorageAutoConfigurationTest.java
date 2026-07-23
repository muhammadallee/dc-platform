package ae.gov.dubaicustoms.platform.storage.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import ae.gov.dubaicustoms.platform.storage.autoconfigure.internal.ChecksumObjectStore;
import ae.gov.dubaicustoms.platform.storage.autoconfigure.internal.ObservedObjectStore;
import ae.gov.dubaicustoms.platform.storage.autoconfigure.internal.StorageDecorators;
import ae.gov.dubaicustoms.platform.storage.fs.FsObjectStore;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

/** ContextRunner matrix, provider selection, and decorator behaviour (checksum + observation). */
class PlatformStorageAutoConfigurationTest {

    @TempDir
    Path storageRoot;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    S3ObjectStoreAutoConfiguration.class,
                    FsObjectStoreAutoConfiguration.class,
                    PlatformStorageAutoConfiguration.class));

    private ApplicationContextRunner withFsRoot() {
        return runner.withPropertyValues("dc.platform.storage.fs.root=" + storageRoot.toString().replace("\\", "/"));
    }

    @Test
    void activeByDefaultUsesTheFilesystemProvider() {
        withFsRoot().run(context -> {
            assertThat(context).hasSingleBean(ObjectStore.class);
            assertThat(StorageDecorators.providerName(context.getBean(ObjectStore.class))).isEqualTo("fs");
            assertThat(context).getBean(CapabilityDescriptor.class)
                    .extracting(CapabilityDescriptor::detail).isEqualTo("fs");
        });
    }

    @Test
    void killSwitchDisables() {
        withFsRoot().withPropertyValues("dc.platform.storage.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ObjectStore.class);
            assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void backsOffWhenUserObjectStorePresent() {
        ObjectStore mine = new NoopObjectStore();
        withFsRoot().withBean("mine", ObjectStore.class, () -> mine)
                .run(context -> assertThat(context.getBean(ObjectStore.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenNoProviderOnClasspath() {
        withFsRoot()
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        FsObjectStore.class, S3Client.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ObjectStore.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void s3WinsWhenAnS3ClientIsPresent() {
        withFsRoot().withBean(S3Client.class, () -> mock(S3Client.class)).run(context -> {
            assertThat(context).hasSingleBean(ObjectStore.class);
            assertThat(StorageDecorators.providerName(context.getBean(ObjectStore.class))).isEqualTo("s3");
            assertThat(context).getBean(CapabilityDescriptor.class)
                    .extracting(CapabilityDescriptor::detail).isEqualTo("s3");
        });
    }

    @Test
    void checksumTagIsStoredOnPutByDefault() {
        withFsRoot().run(context -> {
            ObjectStore store = context.getBean(ObjectStore.class);
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            store.put("docs", "a.txt", new ByteArrayInputStream(body),
                    new ObjectMetadata("text/plain", body.length, Map.of()));
            try (StoredObject obj = store.get("docs", "a.txt").orElseThrow()) {
                assertThat(obj.metadata().userTags()).containsKey(ChecksumObjectStore.CHECKSUM_TAG);
                // sha256("hello")
                assertThat(obj.metadata().userTags().get(ChecksumObjectStore.CHECKSUM_TAG))
                        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
            }
        });
    }

    @Test
    void checksumCanBeDisabled() {
        withFsRoot().withPropertyValues("dc.platform.storage.checksum.enabled=false").run(context -> {
            ObjectStore store = context.getBean(ObjectStore.class);
            assertThat(store).isNotInstanceOf(ChecksumObjectStore.class);
            byte[] body = "hi".getBytes(StandardCharsets.UTF_8);
            store.put("docs", "b.txt", new ByteArrayInputStream(body),
                    new ObjectMetadata("text/plain", body.length, Map.of()));
            try (StoredObject obj = store.get("docs", "b.txt").orElseThrow()) {
                assertThat(obj.metadata().userTags()).doesNotContainKey(ChecksumObjectStore.CHECKSUM_TAG);
            }
        });
    }

    @Test
    void wrapsInObservationWhenARegistryIsPresent() {
        withFsRoot().withBean(ObservationRegistry.class, ObservationRegistry::create).run(context -> {
            assertThat(context.getBean(ObjectStore.class)).isInstanceOf(ObservedObjectStore.class);
        });
    }

    @Test
    void allOperationsFlowThroughTheObservationAndChecksumDecorators() {
        withFsRoot().withBean(ObservationRegistry.class, ObservationRegistry::create).run(context -> {
            ObjectStore store = context.getBean(ObjectStore.class);
            byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

            store.put("docs", "x/y.txt", new ByteArrayInputStream(body),
                    new ObjectMetadata("text/plain", body.length, Map.of()));

            try (StoredObject obj = store.get("docs", "x/y.txt").orElseThrow()) {
                assertThat(new String(obj.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload");
            }
            try (Stream<ObjectSummary> listing = store.list("docs", "x/")) {
                assertThat(listing.map(ObjectSummary::key)).containsExactly("x/y.txt");
            }
            assertThat(store.delete("docs", "x/y.txt")).isTrue();
            assertThat(store.get("docs", "x/y.txt")).isEmpty();
        });
    }

    @Test
    void fsRootDefaultsToTmpDirWhenUnset() {
        StorageProperties.Fs unset = new StorageProperties.Fs(null);
        assertThat(unset.resolvedRoot()).isEqualTo(
                Path.of(System.getProperty("java.io.tmpdir"), "dc-storage"));
        StorageProperties.Fs set = new StorageProperties.Fs(storageRoot.toString());
        assertThat(set.resolvedRoot()).isEqualTo(Path.of(storageRoot.toString()));
    }

    private static final class NoopObjectStore implements ObjectStore {
        @Override
        public ae.gov.dubaicustoms.platform.storage.ObjectRef put(
                String bucket, String key, java.io.InputStream in, ObjectMetadata meta) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredObject> get(String bucket, String key) {
            return Optional.empty();
        }

        @Override
        public boolean delete(String bucket, String key) {
            return false;
        }

        @Override
        public Stream<ObjectSummary> list(String bucket, String prefix) {
            return Stream.empty();
        }
    }
}
