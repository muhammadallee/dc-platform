package ae.gov.dubaicustoms.platform.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * A lightweight listing entry from {@link ObjectStore#list}: the object's key, byte size and last
 * modification time, without its content or user tags.
 *
 * <p>Value object; immutable and thread-safe. Components are never {@code null}.
 *
 * @param key the object key within the listed bucket
 * @param size the object size in bytes; {@code >= 0}
 * @param lastModified the last modification instant reported by the backend
 * @since 0.2.0
 */
public record ObjectSummary(String key, long size, Instant lastModified) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code key} or {@code lastModified} is null
     * @throws IllegalArgumentException if {@code size} is negative
     */
    public ObjectSummary {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(lastModified, "lastModified must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
    }
}
