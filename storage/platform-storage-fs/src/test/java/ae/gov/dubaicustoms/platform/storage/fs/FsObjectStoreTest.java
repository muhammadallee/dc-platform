package ae.gov.dubaicustoms.platform.storage.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behaviour of the filesystem provider against a real temp directory — no Docker. */
class FsObjectStoreTest {

    @TempDir
    Path root;

    private FsObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FsObjectStore(root);
    }

    private ObjectRef put(String bucket, String key, String content, Map<String, String> tags) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return store.put(bucket, key, new ByteArrayInputStream(bytes),
                new ObjectMetadata("text/plain", bytes.length, tags));
    }

    @Test
    void putThenGetRoundTripsContentAndMetadata() throws IOException {
        ObjectRef ref = put("docs", "a/b/hello.txt", "hello world", Map.of("owner", "trade"));

        assertThat(ref.bucket()).isEqualTo("docs");
        assertThat(ref.key()).isEqualTo("a/b/hello.txt");
        assertThat(ref.size()).isEqualTo(11);
        assertThat(ref.etag()).hasSize(64); // sha-256 hex

        try (StoredObject obj = store.get("docs", "a/b/hello.txt").orElseThrow()) {
            assertThat(obj.metadata().contentType()).isEqualTo("text/plain");
            assertThat(obj.metadata().contentLength()).isEqualTo(11);
            assertThat(obj.metadata().userTags()).containsEntry("owner", "trade");
            assertThat(new String(obj.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        }
    }

    @Test
    void getMissingReturnsEmpty() {
        assertThat(store.get("docs", "nope.txt")).isEmpty();
    }

    @Test
    void deleteReportsWhetherObjectExisted() {
        put("docs", "gone.txt", "x", Map.of());
        assertThat(store.delete("docs", "gone.txt")).isTrue();
        assertThat(store.delete("docs", "gone.txt")).isFalse();
        assertThat(store.get("docs", "gone.txt")).isEmpty();
    }

    @Test
    void listReturnsPrefixMatchesAndExcludesSidecars() {
        put("docs", "2026/a.txt", "a", Map.of());
        put("docs", "2026/b.txt", "b", Map.of());
        put("docs", "2025/c.txt", "c", Map.of());

        try (Stream<ObjectSummary> listing = store.list("docs", "2026/")) {
            List<String> keys = listing.map(ObjectSummary::key).sorted().toList();
            assertThat(keys).containsExactly("2026/a.txt", "2026/b.txt");
        }
    }

    @Test
    void listOnUnknownBucketIsEmpty() {
        try (Stream<ObjectSummary> listing = store.list("ghost", "")) {
            assertThat(listing).isEmpty();
        }
    }

    @Test
    void traversalKeyIsRejectedAndNothingIsWrittenOutsideRoot() {
        assertThatThrownBy(() -> put("docs", "../escape.txt", "x", Map.of()))
                .isInstanceOf(ObjectStoreException.class)
                .satisfies(ex -> assertThat(((ObjectStoreException) ex).code())
                        .isEqualTo(ObjectStoreException.INVALID_KEY));
        assertThat(Files.exists(root.getParent().resolve("escape.txt"))).isFalse();
    }

    @Test
    void constructorFailsWhenRootPathIsAFile() throws IOException {
        Path file = Files.writeString(root.resolve("not-a-dir"), "x");
        assertThatThrownBy(() -> new FsObjectStore(file))
                .isInstanceOf(ObjectStoreException.class)
                .satisfies(ex -> assertThat(((ObjectStoreException) ex).code()).isEqualTo(ObjectStoreException.IO));
    }

    @Test
    void putFailsWhenKeyParentCollidesWithAnExistingObject() {
        put("docs", "collision", "leaf", Map.of());
        // 'collision' is now a file; putting under it forces createDirectories over a file -> IO error.
        assertThatThrownBy(() -> put("docs", "collision/child.txt", "x", Map.of()))
                .isInstanceOf(ObjectStoreException.class)
                .satisfies(ex -> assertThat(((ObjectStoreException) ex).code()).isEqualTo(ObjectStoreException.IO));
    }

    @Test
    void getFailsOnCorruptSidecar() throws IOException {
        put("docs", "corrupt.txt", "body", Map.of());
        // Overwrite the sidecar with invalid JSON: read must surface an IO ObjectStoreException.
        Files.writeString(root.resolve("docs").resolve("corrupt.txt.meta.json"), "{ not json");
        assertThatThrownBy(() -> store.get("docs", "corrupt.txt"))
                .isInstanceOf(ObjectStoreException.class)
                .satisfies(ex -> assertThat(((ObjectStoreException) ex).code()).isEqualTo(ObjectStoreException.IO));
    }

    @Test
    void readsObjectWrittenWithoutASidecar() throws IOException {
        Path bucketDir = Files.createDirectories(root.resolve("docs"));
        Files.writeString(bucketDir.resolve("raw.bin"), "raw");

        Optional<StoredObject> obj = store.get("docs", "raw.bin");
        assertThat(obj).isPresent();
        try (StoredObject stored = obj.get()) {
            assertThat(stored.metadata().contentType()).isEqualTo("application/octet-stream");
            assertThat(stored.metadata().contentLength()).isEqualTo(3);
        }
    }
}
