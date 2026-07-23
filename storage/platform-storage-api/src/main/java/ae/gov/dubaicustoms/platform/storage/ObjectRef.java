package ae.gov.dubaicustoms.platform.storage;

import java.util.Objects;

/**
 * A reference to a stored object, returned by {@link ObjectStore#put}: where it lives plus the
 * provider's entity tag and the number of bytes actually stored.
 *
 * <p>The {@code etag} is opaque and provider-specific (a content hash for the filesystem provider, the
 * S3 ETag for the S3 provider); use it for change detection, not as a cross-provider checksum.
 *
 * <p>Value object; immutable and thread-safe. Components are never {@code null}.
 *
 * @param bucket the container the object was stored in
 * @param key the object key within the bucket
 * @param etag the provider's opaque entity tag for the stored content
 * @param size the number of bytes stored; {@code >= 0}
 * @since 0.2.0
 */
public record ObjectRef(String bucket, String key, String etag, long size) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code bucket}, {@code key} or {@code etag} is null
     * @throws IllegalArgumentException if {@code size} is negative
     */
    public ObjectRef {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(etag, "etag must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
    }
}
