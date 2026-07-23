/**
 * The storage capability contract: {@link ae.gov.dubaicustoms.platform.storage.ObjectStore} moves
 * opaque blobs to and from a backend addressed by {@code (bucket, key)}, streaming-first and failing
 * with {@link ae.gov.dubaicustoms.platform.storage.ObjectStoreException}.
 *
 * <p>Applications depend only on this module; {@code platform-storage-spi} defines the shared provider
 * helpers, and {@code platform-storage-autoconfigure} supplies the implementation over whichever
 * provider is on the classpath (filesystem by default, S3 when present).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.storage;
