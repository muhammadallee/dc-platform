package ae.gov.dubaicustoms.platform.files;

import java.util.Optional;
import org.apiguardian.api.API;

/**
 * Detects a file's content type from its bytes (magic-number sniffing), so uploads are validated by
 * what they actually are — not by the client-supplied extension or {@code Content-Type}, which are
 * trivially forged. The platform ships a validator for a small v1 set (PDF, PNG, JPEG, ZIP, CSV,
 * plain text); applications inject it or supply their own.
 *
 * <pre>{@code
 * if (!validator.permits(uploadedBytes, policy)) {
 *     throw new BadUpload("content type not allowed");
 * }
 * }</pre>
 *
 * <p>Thread-safe; a single instance is shared.
 *
 * @since 0.2.0
 */
@FunctionalInterface
@API(status = API.Status.STABLE, since = "0.1.0")
public interface ContentTypeValidator {

    /**
     * Sniffs the content type of {@code content} from its leading bytes.
     *
     * @param content the file bytes (or a prefix long enough to cover the magic number); never
     *     {@code null}
     * @return the detected IANA media type, or empty when the content is not recognised
     */
    Optional<String> sniff(byte[] content);

    /**
     * Whether {@code content} sniffs to a type permitted by {@code policy}.
     *
     * @param content the file bytes; never {@code null}
     * @param policy the upload policy to check against; never {@code null}
     * @return {@code true} only if the sniffed type is on the policy's allow-list
     */
    default boolean permits(byte[] content, FileUploadPolicy policy) {
        return sniff(content).map(policy::allowsType).orElse(false);
    }
}
