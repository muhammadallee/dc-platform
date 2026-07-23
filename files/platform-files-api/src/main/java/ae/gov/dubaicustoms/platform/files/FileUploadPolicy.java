package ae.gov.dubaicustoms.platform.files;

import java.util.Objects;
import java.util.Set;

/**
 * The rules an uploaded file must satisfy: a maximum size and an allow-list of content types. The
 * content type is matched against what the bytes actually are (via {@link ContentTypeValidator} — a
 * magic-byte sniff), never against the client-supplied extension or {@code Content-Type} header, both
 * of which are trivially forged.
 *
 * <pre>{@code
 * var policy = new FileUploadPolicy(5 * 1024 * 1024, Set.of("application/pdf", "image/png"));
 * if (!policy.allowsSize(bytes.length) || !validator.permits(bytes, policy)) {
 *     throw new BadUpload();
 * }
 * }</pre>
 *
 * <p>Value object; immutable and thread-safe. {@code allowedTypes} is defensively copied into an
 * immutable set.
 *
 * @param maxSizeBytes the maximum permitted size in bytes; must be positive
 * @param allowedTypes the permitted content types (IANA media types); never {@code null}
 * @since 0.2.0
 */
public record FileUploadPolicy(long maxSizeBytes, Set<String> allowedTypes) {

    /**
     * Validates the size bound and defensively copies {@code allowedTypes}.
     *
     * @throws IllegalArgumentException if {@code maxSizeBytes} is not positive
     * @throws NullPointerException if {@code allowedTypes} is null
     */
    public FileUploadPolicy {
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes must be positive: " + maxSizeBytes);
        }
        allowedTypes = Set.copyOf(Objects.requireNonNull(allowedTypes, "allowedTypes must not be null"));
    }

    /**
     * Whether {@code bytes} is within the size bound.
     *
     * @param bytes the candidate size in bytes
     * @return {@code true} if non-negative and not greater than {@link #maxSizeBytes()}
     */
    public boolean allowsSize(long bytes) {
        return bytes >= 0 && bytes <= maxSizeBytes;
    }

    /**
     * Whether {@code contentType} is on the allow-list.
     *
     * @param contentType the sniffed content type
     * @return {@code true} if permitted
     */
    public boolean allowsType(String contentType) {
        return allowedTypes.contains(contentType);
    }
}
