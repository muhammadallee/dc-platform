package ae.gov.dubaicustoms.platform.files.autoconfigure;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the files capability. Bound from {@code dc.platform.files.*}.
 *
 * <p>Immutable; validated at startup. Registered via {@code @EnableConfigurationProperties} (never
 * scanned). Supplies the default upload policy (size + allowed content types) and the servlet
 * multipart size limits.
 *
 * @param enabled master kill switch for the whole capability
 * @param maxFileSize the largest single uploaded file permitted (also applied to servlet multipart)
 * @param maxRequestSize the largest total multipart request permitted
 * @param allowedTypes the content types the default {@code FileUploadPolicy} permits
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.files")
public record FilesProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Largest single uploaded file; also configures the servlet multipart max file size. */
        @DefaultValue("10MB") DataSize maxFileSize,
        /** Largest total multipart request. */
        @DefaultValue("10MB") DataSize maxRequestSize,
        /** Content types the default FileUploadPolicy permits (the sniffable v1 set). */
        @DefaultValue({"application/pdf", "image/png", "image/jpeg", "application/zip",
                "text/csv", "text/plain"}) Set<String> allowedTypes) {
}
