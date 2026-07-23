package ae.gov.dubaicustoms.platform.files.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Behavior of the storage-to-HTTP streaming helper: 200 with headers + body, or 404. */
class StreamingDownloadsTest {

    @Test
    void streamsObjectWithMetadataHeaders() throws Exception {
        byte[] content = "%PDF-1.7 body".getBytes(StandardCharsets.US_ASCII);
        ObjectStore store = new FixedObjectStore(new StoredObject(
                new ObjectMetadata("application/pdf", content.length, Map.of()),
                new ByteArrayInputStream(content)));

        ResponseEntity<StreamingResponseBody> response =
                StreamingDownloads.write(store, "invoices", "../secret/report.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(content.length);
        // The download filename is sanitised (directory component stripped).
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename=\"report.pdf\"");

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        response.getBody().writeTo(sink);
        assertThat(sink.toByteArray()).isEqualTo(content);
    }

    @Test
    void returnsNotFoundWhenObjectAbsent() {
        ResponseEntity<StreamingResponseBody> response =
                StreamingDownloads.write(new FixedObjectStore(null), "invoices", "missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    /** An ObjectStore that returns a preset object (or nothing) from get; other operations unused. */
    private record FixedObjectStore(StoredObject object) implements ObjectStore {

        @Override
        public ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredObject> get(String bucket, String key) {
            return Optional.ofNullable(object);
        }

        @Override
        public boolean delete(String bucket, String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<ObjectSummary> list(String bucket, String prefix) {
            throw new UnsupportedOperationException();
        }
    }
}
