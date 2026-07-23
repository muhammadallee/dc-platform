package ae.gov.dubaicustoms.platform.storage.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;

/**
 * Builds the decorated {@code ObjectStore} the platform exposes: the raw provider, optionally wrapped
 * for checksum-on-put and observation. Central so both provider auto-configurations decorate
 * identically.
 */
public final class StorageDecorators {

    private StorageDecorators() {
    }

    /**
     * Decorates {@code raw} with checksum (when {@code checksumEnabled}) and observation (when a
     * {@code registry} is present).
     *
     * @param raw the provider store
     * @param checksumEnabled whether to add the SHA-256 checksum tag on put
     * @param registry the observation registry, or {@code null} to skip observation
     * @return the decorated store (or {@code raw} when nothing is enabled)
     */
    public static ObjectStore decorate(ObjectStore raw, boolean checksumEnabled, ObservationRegistry registry) {
        ObjectStore result = raw;
        if (checksumEnabled) {
            result = new ChecksumObjectStore(result);
        }
        if (registry != null) {
            result = new ObservedObjectStore(result, registry, providerName(raw));
        }
        return result;
    }

    /**
     * The provider name for a (possibly decorated) store: unwraps decorators to the base provider and
     * derives its short name (e.g. {@code FsObjectStore} → {@code fs}).
     */
    public static String providerName(ObjectStore store) {
        ObjectStore current = store;
        while (current instanceof DelegatingObjectStore delegating) {
            current = delegating.delegate();
        }
        return current.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("objectstore", "");
    }
}
