package ae.gov.dubaicustoms.platform.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Proves the S3 provider against a real S3 API (LocalStack): put/get/list/delete round-trip and
 * metadata survives the wire. Excluded from the default build (docker JUnit tag); run under
 * {@code -Pdocker}.
 */
@Tag("docker")
@Testcontainers
class S3ObjectStoreIT {

    @Container
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:3")
            .withExposedPorts(4566)
            .withEnv("SERVICES", "s3");

    private S3Client s3;
    private S3ObjectStore store;

    @BeforeEach
    void setUp() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .build();
        s3.createBucket(CreateBucketRequest.builder().bucket("docs").build());
        store = new S3ObjectStore(s3);
    }

    @AfterEach
    void tearDown() {
        s3.close();
    }

    @Test
    void putGetListDeleteRoundTrip() throws Exception {
        byte[] body = "hello s3".getBytes(StandardCharsets.UTF_8);
        ObjectRef ref = store.put("docs", "2026/a.txt", new ByteArrayInputStream(body),
                new ObjectMetadata("text/plain", body.length, Map.of("owner", "trade")));
        assertThat(ref.etag()).isNotBlank();

        try (StoredObject obj = store.get("docs", "2026/a.txt").orElseThrow()) {
            assertThat(obj.metadata().contentType()).isEqualTo("text/plain");
            assertThat(obj.metadata().userTags()).containsEntry("owner", "trade");
            assertThat(new String(obj.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello s3");
        }

        try (Stream<ObjectSummary> listing = store.list("docs", "2026/")) {
            List<String> keys = listing.map(ObjectSummary::key).toList();
            assertThat(keys).containsExactly("2026/a.txt");
        }

        assertThat(store.delete("docs", "2026/a.txt")).isTrue();
        assertThat(store.get("docs", "2026/a.txt")).isEmpty();
        assertThat(store.delete("docs", "2026/a.txt")).isFalse();
    }
}
