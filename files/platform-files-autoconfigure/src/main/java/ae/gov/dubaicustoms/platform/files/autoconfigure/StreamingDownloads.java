package ae.gov.dubaicustoms.platform.files.autoconfigure;

import ae.gov.dubaicustoms.platform.files.SafeFilename;
import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Streams a stored object straight to the HTTP response without buffering it in heap: the object's
 * {@code InputStream} is copied to the servlet output stream lazily by Spring MVC and closed
 * afterwards. Returns 404 when the object is absent.
 *
 * <pre>{@code
 * @GetMapping("/files/{key}")
 * ResponseEntity<StreamingResponseBody> download(@PathVariable String key) {
 *     return StreamingDownloads.write(objectStore, "invoices", key);
 * }
 * }</pre>
 *
 * <p>Lives in the files autoconfigure module (not the dependency-poor api) because it bridges the
 * storage capability's {@link ObjectStore} and Spring MVC's {@link StreamingResponseBody} — types the
 * constitution forbids in an api signature (decision D56). The download filename is run through
 * {@link SafeFilename} before it goes into the {@code Content-Disposition} header.
 *
 * @since 0.2.0
 */
public final class StreamingDownloads {

    private StreamingDownloads() {
    }

    /**
     * Builds a streaming download response for the object at {@code bucket}/{@code key}.
     *
     * @param store the object store to read from; never {@code null}
     * @param bucket the bucket; never {@code null}
     * @param key the object key (also the download filename); never {@code null}
     * @return a 200 streaming response, or 404 when the object does not exist
     */
    public static ResponseEntity<StreamingResponseBody> write(ObjectStore store, String bucket, String key) {
        Optional<StoredObject> found = store.get(bucket, key);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        StoredObject object = found.get();
        ObjectMetadata metadata = object.metadata();
        StreamingResponseBody body = output -> {
            try (StoredObject open = object) {
                open.content().transferTo(output);
            }
        };
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + SafeFilename.sanitize(key) + "\"");
        if (metadata.contentType() != null) {
            builder.contentType(MediaType.parseMediaType(metadata.contentType()));
        }
        if (metadata.contentLength() > 0) {
            builder.contentLength(metadata.contentLength());
        }
        return builder.body(body);
    }
}
