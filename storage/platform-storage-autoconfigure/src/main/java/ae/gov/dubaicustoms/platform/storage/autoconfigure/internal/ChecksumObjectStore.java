package ae.gov.dubaicustoms.platform.storage.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Adds a SHA-256 checksum to every {@code put} as the {@code sha256} user tag. Exists so an operator
 * can verify object integrity later regardless of the provider's own (md5/opaque) etag.
 *
 * <p>To store the checksum as a tag it must be known before the object is written, but the API is
 * streaming-first and an {@link InputStream} cannot be read twice. Rather than buffer into heap (the
 * exact footgun the API forbids), the content is spooled once through a {@link DigestInputStream} to a
 * temp file, then handed to the delegate — bounded by disk, not memory (decision D49).
 */
public final class ChecksumObjectStore implements DelegatingObjectStore {

    /** User-tag key under which the put-time SHA-256 (hex) is stored. */
    public static final String CHECKSUM_TAG = "sha256";

    private final ObjectStore delegate;

    public ChecksumObjectStore(ObjectStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public ObjectStore delegate() {
        return delegate;
    }

    @Override
    public ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta) {
        Path spool = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            spool = Files.createTempFile("dc-storage-checksum-", ".tmp");
            try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
                Files.copy(digesting, spool, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String hex = HexFormat.of().formatHex(digest.digest());
            Map<String, String> tags = new HashMap<>(meta.userTags());
            tags.put(CHECKSUM_TAG, hex);
            ObjectMetadata tagged = new ObjectMetadata(meta.contentType(), Files.size(spool), tags);
            try (InputStream spooled = Files.newInputStream(spool)) {
                return delegate.put(bucket, key, spooled, tagged);
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to checksum " + bucket + "/" + key, e);
        } finally {
            if (spool != null) {
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException ignored) {
                    // Best-effort temp cleanup; the OS reaps the temp dir. Not worth failing the put.
                }
            }
        }
    }

    @Override
    public Optional<StoredObject> get(String bucket, String key) {
        return delegate.get(bucket, key);
    }

    @Override
    public boolean delete(String bucket, String key) {
        return delegate.delete(bucket, key);
    }

    @Override
    public Stream<ObjectSummary> list(String bucket, String prefix) {
        return delegate.list(bucket, prefix);
    }
}
