package ae.gov.dubaicustoms.platform.storage.fs;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import ae.gov.dubaicustoms.platform.storage.fs.internal.MetadataSidecar;
import ae.gov.dubaicustoms.platform.storage.spi.KeyValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem {@link ObjectStore}: each object is a file at {@code <root>/<bucket>/<key>} with a JSON
 * metadata sidecar alongside it. The default, reference provider — fully local, no external service.
 *
 * <p>Every operation validates the bucket and key with {@link KeyValidator} and, as defence in depth,
 * verifies the resolved path stays inside the bucket directory, so a crafted key can never read or
 * write outside {@code root}.
 *
 * <p>Thread-safe: holds only an immutable root path; concurrent operations on distinct keys are
 * independent, and the JDK filesystem calls used here are individually atomic enough for the
 * last-write-wins contract of {@link ObjectStore}.
 *
 * @since 0.2.0
 */
public final class FsObjectStore implements ObjectStore {

    private final Path root;
    private final KeyValidator keys = new KeyValidator();

    /**
     * Creates a store rooted at {@code root} (created if absent).
     *
     * @param root the directory objects are stored under; never {@code null}
     * @throws ObjectStoreException if the root cannot be created
     */
    public FsObjectStore(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "cannot create storage root " + this.root, e);
        }
    }

    @Override
    public ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta) {
        Objects.requireNonNull(in, "in must not be null");
        Objects.requireNonNull(meta, "meta must not be null");
        Path target = resolve(bucket, key);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
                size = Files.copy(digesting, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String etag = HexFormat.of().formatHex(digest.digest());
            // Persist the metadata the caller supplied, but with the length actually written.
            MetadataSidecar.write(target, new ObjectMetadata(meta.contentType(), size, meta.userTags()));
            return new ObjectRef(bucket, key, etag, size);
        } catch (NoSuchAlgorithmException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "SHA-256 unavailable", e);
        } catch (IOException | UncheckedIOException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to put " + bucket + "/" + key, e);
        }
    }

    @Override
    public Optional<StoredObject> get(String bucket, String key) {
        Path target = resolve(bucket, key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            ObjectMetadata meta = MetadataSidecar.read(target);
            return Optional.of(new StoredObject(meta, Files.newInputStream(target)));
        } catch (IOException | UncheckedIOException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to get " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean delete(String bucket, String key) {
        Path target = resolve(bucket, key);
        try {
            boolean existed = Files.deleteIfExists(target);
            MetadataSidecar.delete(target);
            return existed;
        } catch (IOException | UncheckedIOException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to delete " + bucket + "/" + key, e);
        }
    }

    @Override
    public Stream<ObjectSummary> list(String bucket, String prefix) {
        keys.requireValidBucket(bucket);
        Objects.requireNonNull(prefix, "prefix must not be null");
        Path bucketDir = root.resolve(bucket);
        if (!Files.isDirectory(bucketDir)) {
            return Stream.empty();
        }
        try {
            // Files.walk holds a directory handle; the returned stream is the caller's to close (contract).
            return Files.walk(bucketDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> !MetadataSidecar.isSidecar(path))
                    .map(path -> toSummary(bucketDir, path))
                    .filter(summary -> summary.key().startsWith(prefix));
        } catch (IOException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to list " + bucket, e);
        }
    }

    private ObjectSummary toSummary(Path bucketDir, Path file) {
        try {
            String key = bucketDir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            return new ObjectSummary(key, Files.size(file), Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to summarise " + file, e);
        }
    }

    private Path resolve(String bucket, String key) {
        keys.requireValidBucket(bucket);
        keys.requireValidKey(key);
        Path bucketDir = root.resolve(bucket).normalize();
        Path resolved = bucketDir.resolve(key).normalize();
        if (!resolved.startsWith(bucketDir)) {
            // Defence in depth: KeyValidator already rejected traversal, but never write outside the root.
            throw new ObjectStoreException(ObjectStoreException.INVALID_KEY,
                    "resolved path escapes bucket: " + bucket + "/" + key);
        }
        return resolved;
    }
}
