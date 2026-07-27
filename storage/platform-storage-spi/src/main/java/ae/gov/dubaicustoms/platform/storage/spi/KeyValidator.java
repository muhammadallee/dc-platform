package ae.gov.dubaicustoms.platform.storage.spi;

import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import org.apiguardian.api.API;

/**
 * Validates bucket names and object keys before a provider touches the backend, rejecting anything
 * that could escape its intended container — the single security-critical helper every
 * {@code ObjectStore} provider must apply.
 *
 * <p>Keys are treated as forward-slash-delimited logical paths. A key is rejected when it is blank,
 * absolute, contains a backslash or a NUL, or contains a {@code .} or {@code ..} path segment — the
 * last being the filesystem provider's path-traversal vector ({@code ../../etc/passwd}) and a
 * prefix-confusion vector for object stores. Validation is provider-independent so the same guarantee
 * holds whether the backend is a directory tree or S3.
 *
 * <pre>{@code
 * private final KeyValidator keys = new KeyValidator();
 * String safe = keys.requireValidKey(key);   // throws ObjectStoreException(INVALID_KEY) on traversal
 * }</pre>
 *
 * <p>Stateless and thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public final class KeyValidator {

    /**
     * Validates a bucket name.
     *
     * @param bucket the bucket name
     * @return {@code bucket} unchanged when valid
     * @throws ObjectStoreException with {@link ObjectStoreException#INVALID_KEY} if the name is blank,
     *     or contains a slash, backslash or NUL
     */
    public String requireValidBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new ObjectStoreException(ObjectStoreException.INVALID_KEY, "bucket must not be blank");
        }
        if (bucket.indexOf('/') >= 0 || bucket.indexOf('\\') >= 0 || bucket.indexOf('\0') >= 0) {
            throw new ObjectStoreException(ObjectStoreException.INVALID_KEY,
                    "bucket must not contain '/', '\\' or NUL: '" + bucket + "'");
        }
        return bucket;
    }

    /**
     * Validates an object key.
     *
     * @param key the object key
     * @return {@code key} unchanged when valid
     * @throws ObjectStoreException with {@link ObjectStoreException#INVALID_KEY} if the key is blank,
     *     absolute, contains a backslash or NUL, or contains a {@code .} / {@code ..} segment
     */
    public String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ObjectStoreException(ObjectStoreException.INVALID_KEY, "key must not be blank");
        }
        if (key.indexOf('\\') >= 0 || key.indexOf('\0') >= 0) {
            throw new ObjectStoreException(ObjectStoreException.INVALID_KEY,
                    "key must not contain '\\' or NUL: '" + key + "'");
        }
        if (key.startsWith("/")) {
            throw new ObjectStoreException(ObjectStoreException.INVALID_KEY,
                    "key must be relative (no leading '/'): '" + key + "'");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new ObjectStoreException(ObjectStoreException.INVALID_KEY,
                        "key must not contain a '.' or '..' path segment: '" + key + "'");
            }
        }
        return key;
    }
}
