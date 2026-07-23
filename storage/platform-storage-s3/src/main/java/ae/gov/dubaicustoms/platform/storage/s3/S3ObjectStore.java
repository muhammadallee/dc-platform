package ae.gov.dubaicustoms.platform.storage.s3;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import ae.gov.dubaicustoms.platform.storage.spi.KeyValidator;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3 {@link ObjectStore} over the AWS SDK v2 {@link S3Client}: content type and user tags travel as S3
 * object metadata, and buckets/keys map straight through after {@link KeyValidator} vetting.
 *
 * <p>The provider does not create or manage buckets — a bucket is expected to exist (created by
 * infrastructure/IaC); operations against a missing bucket surface as {@link ObjectStoreException}.
 *
 * <p>Thread-safe: the underlying {@link S3Client} is thread-safe and this class holds no mutable state.
 *
 * @since 0.2.0
 */
public final class S3ObjectStore implements ObjectStore {

    private final S3Client s3;
    private final KeyValidator keys = new KeyValidator();

    /**
     * Creates a store over the given client.
     *
     * @param s3 the configured S3 client; never {@code null}
     */
    public S3ObjectStore(S3Client s3) {
        this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
    }

    @Override
    public ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta) {
        keys.requireValidBucket(bucket);
        keys.requireValidKey(key);
        Objects.requireNonNull(in, "in must not be null");
        Objects.requireNonNull(meta, "meta must not be null");
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(meta.contentType())
                    .metadata(meta.userTags())
                    .build();
            PutObjectResponse response = s3.putObject(request, RequestBody.fromInputStream(in, meta.contentLength()));
            return new ObjectRef(bucket, key, response.eTag(), meta.contentLength());
        } catch (S3Exception e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to put " + bucket + "/" + key, e);
        }
    }

    @Override
    public Optional<StoredObject> get(String bucket, String key) {
        keys.requireValidBucket(bucket);
        keys.requireValidKey(key);
        try {
            ResponseInputStream<GetObjectResponse> response =
                    s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            GetObjectResponse meta = response.response();
            long length = meta.contentLength() == null ? 0L : meta.contentLength();
            String contentType = meta.contentType() == null ? "application/octet-stream" : meta.contentType();
            return Optional.of(new StoredObject(new ObjectMetadata(contentType, length, meta.metadata()), response));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to get " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean delete(String bucket, String key) {
        keys.requireValidBucket(bucket);
        keys.requireValidKey(key);
        try {
            // S3 delete is idempotent (no 404), so head first to honour the "existed?" return contract.
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to stat " + bucket + "/" + key, e);
        }
        try {
            s3.deleteObject(builder -> builder.bucket(bucket).key(key));
            return true;
        } catch (S3Exception e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to delete " + bucket + "/" + key, e);
        }
    }

    @Override
    public Stream<ObjectSummary> list(String bucket, String prefix) {
        keys.requireValidBucket(bucket);
        Objects.requireNonNull(prefix, "prefix must not be null");
        List<ObjectSummary> summaries = new ArrayList<>();
        String continuationToken = null;
        try {
            do {
                ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .continuationToken(continuationToken)
                        .build());
                for (S3Object object : response.contents()) {
                    summaries.add(new ObjectSummary(object.key(), object.size(), object.lastModified()));
                }
                continuationToken = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (S3Exception e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "failed to list " + bucket, e);
        }
        return summaries.stream();
    }
}
