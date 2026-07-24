package ae.gov.dubaicustoms.platform.tck.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract test (Technology Compatibility Kit) for {@link ObjectStore} providers. Extend this class,
 * supply a fresh store from {@link #store()}, and inherit the whole suite; a provider is
 * <em>platform-certified</em> for storage iff this class passes against it.
 *
 * <p>Invariants verified:
 * <ol>
 *   <li>{@code put} then {@code get} round-trips the content and its content type;</li>
 *   <li>{@code get} of an absent object returns {@link java.util.Optional#empty()};</li>
 *   <li>{@code delete} removes the object and reports {@code true}; deleting an absent object reports
 *       {@code false};</li>
 *   <li>a second {@code put} overwrites the first (last-write-wins);</li>
 *   <li>user metadata tags and the byte count are preserved;</li>
 *   <li>{@code put} returns an {@link ObjectRef} with a non-blank etag and the stored size;</li>
 *   <li>{@code list} returns exactly the keys under the given prefix;</li>
 *   <li>large content round-trips intact;</li>
 *   <li>{@code put} rejects null content and metadata.</li>
 * </ol>
 *
 * @since 0.2.0
 */
public abstract class ObjectStoreTck {

    private static final String BUCKET = "docs";

    private ObjectStore subject;

    /**
     * Supplies a fresh, empty store for one test.
     *
     * @return a new {@link ObjectStore}; never {@code null}
     */
    protected abstract ObjectStore store();

    @BeforeEach
    void createSubject() {
        subject = store();
    }

    @Test
    void putThenGetRoundTripsContentAndContentType() throws Exception {
        subject.put(BUCKET, "reports/q1.txt", stream("hello world"),
                new ObjectMetadata("text/plain", 11, Map.of()));

        try (StoredObject stored = subject.get(BUCKET, "reports/q1.txt").orElseThrow()) {
            assertThat(new String(stored.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
            assertThat(stored.metadata().contentType()).isEqualTo("text/plain");
        }
    }

    @Test
    void getOfAbsentObjectIsEmpty() {
        assertThat(subject.get(BUCKET, "nope/missing.txt")).isEmpty();
    }

    @Test
    void deleteRemovesAndReportsWhetherItExisted() {
        subject.put(BUCKET, "a.txt", stream("x"), new ObjectMetadata("text/plain", 1, Map.of()));

        assertThat(subject.delete(BUCKET, "a.txt")).isTrue();
        assertThat(subject.get(BUCKET, "a.txt")).isEmpty();
        assertThat(subject.delete(BUCKET, "a.txt")).isFalse();
    }

    @Test
    void putOverwritesExistingContent() throws Exception {
        subject.put(BUCKET, "k.txt", stream("first"), new ObjectMetadata("text/plain", 5, Map.of()));
        subject.put(BUCKET, "k.txt", stream("second"), new ObjectMetadata("text/plain", 6, Map.of()));

        try (StoredObject stored = subject.get(BUCKET, "k.txt").orElseThrow()) {
            assertThat(new String(stored.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("second");
        }
    }

    @Test
    void preservesUserTagsAndByteCount() throws Exception {
        subject.put(BUCKET, "tagged.txt", stream("body"),
                new ObjectMetadata("application/octet-stream", 4, Map.of("owner", "alice", "team", "customs")));

        try (StoredObject stored = subject.get(BUCKET, "tagged.txt").orElseThrow()) {
            assertThat(stored.metadata().userTags()).containsEntry("owner", "alice").containsEntry("team", "customs");
            assertThat(stored.metadata().contentLength()).isEqualTo(4);
        }
    }

    @Test
    void putReturnsARefWithEtagAndSize() {
        ObjectRef ref = subject.put(BUCKET, "ref.txt", stream("abcde"),
                new ObjectMetadata("text/plain", 5, Map.of()));

        assertThat(ref.bucket()).isEqualTo(BUCKET);
        assertThat(ref.key()).isEqualTo("ref.txt");
        assertThat(ref.etag()).isNotBlank();
        assertThat(ref.size()).isEqualTo(5);
    }

    @Test
    void listReturnsKeysUnderThePrefix() {
        subject.put(BUCKET, "reports/q1.txt", stream("1"), new ObjectMetadata("text/plain", 1, Map.of()));
        subject.put(BUCKET, "reports/q2.txt", stream("2"), new ObjectMetadata("text/plain", 1, Map.of()));
        subject.put(BUCKET, "images/logo.png", stream("3"), new ObjectMetadata("image/png", 1, Map.of()));

        try (Stream<ObjectSummary> listed = subject.list(BUCKET, "reports/")) {
            List<String> keys = listed.map(ObjectSummary::key).sorted().toList();
            assertThat(keys).containsExactly("reports/q1.txt", "reports/q2.txt");
        }
    }

    @Test
    void largeContentRoundTripsIntact() throws Exception {
        byte[] payload = new byte[1_048_576];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        subject.put(BUCKET, "big.bin", new ByteArrayInputStream(payload),
                new ObjectMetadata("application/octet-stream", payload.length, Map.of()));

        try (StoredObject stored = subject.get(BUCKET, "big.bin").orElseThrow()) {
            assertThat(stored.content().readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void putRejectsNullContentAndMetadata() {
        assertThatThrownBy(() -> subject.put(BUCKET, "x.txt", null,
                new ObjectMetadata("text/plain", 0, Map.of())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.put(BUCKET, "x.txt", stream("x"), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
