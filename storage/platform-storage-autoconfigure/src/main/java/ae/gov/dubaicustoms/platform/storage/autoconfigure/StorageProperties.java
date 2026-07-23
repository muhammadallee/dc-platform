package ae.gov.dubaicustoms.platform.storage.autoconfigure;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the storage capability. Bound from {@code dc.platform.storage.*}.
 *
 * <p>Immutable; validated at startup. Registered via {@code @EnableConfigurationProperties} (never
 * scanned). The provider is chosen by what is on the classpath (S3 over filesystem), not by a
 * property; this record carries the kill switch, the checksum toggle, and the filesystem root.
 *
 * @param enabled master kill switch for the whole capability
 * @param checksum checksum-on-put settings
 * @param fs filesystem-provider settings
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.storage")
public record StorageProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        @DefaultValue Checksum checksum,
        @DefaultValue Fs fs) {

    /**
     * Checksum-on-put settings.
     *
     * @param enabled whether a SHA-256 checksum is computed on put and stored as the {@code sha256} user tag
     */
    public record Checksum(
            /** Compute a SHA-256 on put and store it as the {@code sha256} user tag. */
            @DefaultValue("true") boolean enabled) {
    }

    /**
     * Filesystem-provider settings.
     *
     * @param root the directory objects are stored under; defaults to {@code ${java.io.tmpdir}/dc-storage}
     */
    public record Fs(
            /** Root directory for the filesystem provider. Defaults to {@code ${java.io.tmpdir}/dc-storage}. */
            String root) {

        /** The configured root, or the {@code ${java.io.tmpdir}/dc-storage} default when unset. */
        public Path resolvedRoot() {
            if (root == null || root.isBlank()) {
                return Path.of(System.getProperty("java.io.tmpdir"), "dc-storage");
            }
            return Path.of(root);
        }
    }
}
