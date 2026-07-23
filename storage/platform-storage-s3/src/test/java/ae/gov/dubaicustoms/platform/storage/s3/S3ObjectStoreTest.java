package ae.gov.dubaicustoms.platform.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Command-level mapping tests against a mocked S3Client — no Docker. */
class S3ObjectStoreTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3ObjectStore store = new S3ObjectStore(s3);

    @Test
    void putMapsContentTypeTagsAndReturnsEtag() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"etag-1\"").build());

        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        ObjectRef ref = store.put("docs", "a/b.txt", new ByteArrayInputStream(body),
                new ObjectMetadata("text/plain", body.length, Map.of("owner", "trade")));

        assertThat(ref.etag()).isEqualTo("\"etag-1\"");
        assertThat(ref.size()).isEqualTo(5);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("docs");
        assertThat(captor.getValue().key()).isEqualTo("a/b.txt");
        assertThat(captor.getValue().contentType()).isEqualTo("text/plain");
        assertThat(captor.getValue().metadata()).containsEntry("owner", "trade");
    }

    @Test
    void getTranslatesResponseMetadataAndContent() throws IOException {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("application/pdf").contentLength(3L).metadata(Map.of("owner", "trade")).build();
        ResponseInputStream<GetObjectResponse> ris = new ResponseInputStream<>(
                response, AbortableInputStream.create(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(ris);

        try (StoredObject obj = store.get("docs", "x.pdf").orElseThrow()) {
            assertThat(obj.metadata().contentType()).isEqualTo("application/pdf");
            assertThat(obj.metadata().contentLength()).isEqualTo(3);
            assertThat(obj.metadata().userTags()).containsEntry("owner", "trade");
            assertThat(new String(obj.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
        }
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(store.get("docs", "nope")).isEmpty();
    }

    @Test
    void deleteReturnsTrueWhenObjectExists() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertThat(store.delete("docs", "x")).isTrue();

        ArgumentCaptor<Consumer<DeleteObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3).deleteObject(captor.capture());
    }

    @Test
    void deleteReturnsFalseWhenObjectMissing() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(store.delete("docs", "x")).isFalse();
    }

    @Test
    void listFollowsContinuationTokensAcrossPages() {
        S3Object first = S3Object.builder().key("2026/a").size(1L).lastModified(Instant.EPOCH).build();
        S3Object second = S3Object.builder().key("2026/b").size(2L).lastModified(Instant.EPOCH).build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(first).isTruncated(true)
                        .nextContinuationToken("tok").build())
                .thenReturn(ListObjectsV2Response.builder().contents(second).isTruncated(false).build());

        try (Stream<ObjectSummary> listing = store.list("docs", "2026/")) {
            assertThat(listing.map(ObjectSummary::key).toList()).containsExactly("2026/a", "2026/b");
        }
    }

    @Test
    void invalidKeyIsRejectedBeforeCallingS3() {
        assertThatThrownBy(() -> store.get("docs", "../escape"))
                .isInstanceOf(ObjectStoreException.class)
                .satisfies(ex -> assertThat(((ObjectStoreException) ex).code())
                        .isEqualTo(ObjectStoreException.INVALID_KEY));
    }

    @Test
    void listNormalisesToSummaries() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());
        try (Stream<ObjectSummary> listing = store.list("docs", "")) {
            assertThat(listing.toList()).isEmpty();
        }
    }
}
