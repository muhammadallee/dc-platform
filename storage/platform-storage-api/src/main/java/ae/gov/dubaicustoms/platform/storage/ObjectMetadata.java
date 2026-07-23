package ae.gov.dubaicustoms.platform.storage;

import java.util.Map;
import java.util.Objects;

/**
 * Content type, byte length and user-defined tags describing an object.
 *
 * <p>{@code userTags} is a defensive, unmodifiable copy of arbitrary string metadata the application
 * chooses to attach (e.g. {@code owner}, {@code classification}); providers persist it alongside the
 * bytes and return it on {@link ObjectStore#get}. The platform may add its own tags (such as the
 * put-time checksum) — those live in the same map on the stored object.
 *
 * <p>Value object; immutable and thread-safe. Components are never {@code null}.
 *
 * @param contentType the MIME type, e.g. {@code application/pdf}; never {@code null}
 * @param contentLength the content length in bytes; must be {@code >= 0}
 * @param userTags arbitrary user metadata; copied defensively, never {@code null}
 * @since 0.2.0
 */
public record ObjectMetadata(String contentType, long contentLength, Map<String, String> userTags) {

    /**
     * Validates and defensively copies the components.
     *
     * @throws NullPointerException if {@code contentType} or {@code userTags} is null
     * @throws IllegalArgumentException if {@code contentLength} is negative
     */
    public ObjectMetadata {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(userTags, "userTags must not be null");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must not be negative: " + contentLength);
        }
        userTags = Map.copyOf(userTags);
    }
}
