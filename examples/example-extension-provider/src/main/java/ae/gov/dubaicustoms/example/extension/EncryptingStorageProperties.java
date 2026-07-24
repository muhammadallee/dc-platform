package ae.gov.dubaicustoms.example.extension;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the example encrypting store, under {@code example.storage.encryption}. The
 * defaults let the service boot with no configuration at all (a fixed demo key and a temp root) so
 * the example is self-contained; a real deployment would supply the key from the platform secrets
 * source and never ship one in a POM or YAML.
 *
 * @param key base64-encoded AES key (128/192/256-bit); defaults to a well-known demo key
 * @param root directory the backing filesystem store writes ciphertext under; defaults to a temp dir
 */
@ConfigurationProperties(prefix = "example.storage.encryption")
public record EncryptingStorageProperties(String key, String root) {

    /** A 32-byte (AES-256) demo key. DEMO ONLY — never reuse a static committed key in production. */
    private static final String DEMO_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    public EncryptingStorageProperties {
        if (key == null || key.isBlank()) {
            key = DEMO_KEY;
        }
        if (root == null || root.isBlank()) {
            root = System.getProperty("java.io.tmpdir") + "/example-extension-store";
        }
    }

    /**
     * @return the configured storage root as a {@link Path}
     */
    public Path resolvedRoot() {
        return Path.of(root);
    }
}
