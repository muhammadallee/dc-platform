package ae.gov.dubaicustoms.platform.storage;

import java.io.InputStream;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Object-storage facade: put, get, delete and list opaque blobs addressed by {@code (bucket, key)}.
 *
 * <p>The contract is deliberately <b>streaming-first</b> — {@link #put} takes an {@link InputStream}
 * and {@link #get} returns one — so a service can move objects far larger than heap without buffering
 * them into a {@code byte[]}. There is intentionally <em>no</em> {@code byte[]} convenience overload:
 * a hidden full-materialisation of a multi-hundred-megabyte object is exactly the footgun this API
 * exists to prevent; callers that genuinely have small in-memory content wrap it in a
 * {@code ByteArrayInputStream} themselves, making the buffering explicit.
 *
 * <pre>{@code
 * ObjectRef ref = store.put("invoices", "2026/07/inv-42.pdf",
 *         Files.newInputStream(pdf), new ObjectMetadata("application/pdf", size, Map.of()));
 *
 * try (StoredObject obj = store.get("invoices", "2026/07/inv-42.pdf").orElseThrow()) {
 *     obj.content().transferTo(out);
 * }
 * }</pre>
 *
 * <p><b>Resource ownership:</b> the {@link InputStream} inside a returned {@link StoredObject} and the
 * {@link Stream} returned by {@link #list} are both owned by the caller and must be closed
 * ({@link StoredObject} is {@link AutoCloseable}; {@code list} should be used in a try-with-resources).
 *
 * <p><b>Thread-safety:</b> implementations must be thread-safe; a single {@code ObjectStore} bean is
 * shared across request threads.
 *
 * @since 0.2.0
 */
public interface ObjectStore {

    /**
     * Stores the full content of {@code in} at {@code (bucket, key)}, overwriting any existing object.
     *
     * <p>The stream is read to end but <b>not</b> closed (the caller owns it). The returned
     * {@link ObjectRef} carries the provider's entity tag and the number of bytes actually stored.
     *
     * @param bucket the container name; must be a valid, non-blank bucket identifier
     * @param key the object key within the bucket; must be a valid, traversal-free key
     * @param in the content to store; read to end, never closed here; never {@code null}
     * @param meta the content type, length and user tags to associate; never {@code null}
     * @return a reference to the stored object (etag + stored size); never {@code null}
     * @throws ObjectStoreException if the key/bucket is invalid or the backend rejects the write
     */
    ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta);

    /**
     * Retrieves the object at {@code (bucket, key)}, if present.
     *
     * @param bucket the container name
     * @param key the object key within the bucket
     * @return the object (metadata + a caller-closed content stream), or empty if no such object exists
     * @throws ObjectStoreException if the key/bucket is invalid or the backend read fails
     */
    Optional<StoredObject> get(String bucket, String key);

    /**
     * Deletes the object at {@code (bucket, key)}.
     *
     * @param bucket the container name
     * @param key the object key within the bucket
     * @return {@code true} if an object existed and was deleted, {@code false} if there was nothing to delete
     * @throws ObjectStoreException if the key/bucket is invalid or the backend delete fails
     */
    boolean delete(String bucket, String key);

    /**
     * Lists the objects in {@code bucket} whose key starts with {@code prefix}, newest listing order
     * unspecified.
     *
     * <p>The returned stream is lazy and holds backend resources (a directory walk or a paged listing);
     * the caller must close it, ideally via try-with-resources.
     *
     * @param bucket the container name
     * @param prefix the key prefix to match; empty matches everything in the bucket
     * @return a caller-closed stream of summaries; never {@code null}
     * @throws ObjectStoreException if the bucket is invalid or the backend listing fails
     */
    Stream<ObjectSummary> list(String bucket, String prefix);
}
