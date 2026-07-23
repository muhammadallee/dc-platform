package ae.gov.dubaicustoms.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Validation rules and resource semantics of the storage API value types. */
class StorageApiTypesTest {

    @Test
    void objectMetadataCopiesTagsDefensivelyAndRejectsNegativeLength() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("owner", "trade");
        ObjectMetadata meta = new ObjectMetadata("application/pdf", 10, mutable);

        mutable.put("owner", "tampered");
        assertThat(meta.userTags()).containsEntry("owner", "trade");
        assertThatThrownBy(() -> meta.userTags().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new ObjectMetadata("text/plain", -1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void objectRefAndSummaryRejectNegativeSizes() {
        assertThatThrownBy(() -> new ObjectRef("b", "k", "etag", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObjectSummary("k", -1, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedObjectClosesItsContentStream() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream tracking = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        try (StoredObject obj = new StoredObject(new ObjectMetadata("text/plain", 0, Map.of()), tracking)) {
            assertThat(obj.content()).isSameAs(tracking);
        }
        assertThat(closed).isTrue();
    }

    @Test
    void objectStoreExceptionCarriesItsCodeAndIsAPlatformException() {
        ObjectStoreException ex = new ObjectStoreException(ObjectStoreException.INVALID_KEY, "bad key");
        assertThat(ex).isInstanceOf(PlatformException.class);
        assertThat(ex.code()).isEqualTo(ObjectStoreException.INVALID_KEY);
        assertThat(ex.code().value()).isEqualTo("DC-STO-0400");
    }
}
