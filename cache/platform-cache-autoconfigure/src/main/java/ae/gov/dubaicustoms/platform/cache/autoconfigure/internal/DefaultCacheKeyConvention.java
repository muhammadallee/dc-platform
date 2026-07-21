package ae.gov.dubaicustoms.platform.cache.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.cache.CacheKeyConvention;
import java.util.Objects;

/**
 * Default {@link CacheKeyConvention}: {@code <appName>:<cacheName>[:<part>]*}, so keys computed by
 * different services never collide in a shared cache backend. Parts are rendered with
 * {@link String#valueOf(Object)}.
 */
public final class DefaultCacheKeyConvention implements CacheKeyConvention {

    private static final char SEPARATOR = ':';

    private final String applicationName;

    /**
     * @param applicationName the prefix applied to every key; never {@code null}
     */
    public DefaultCacheKeyConvention(String applicationName) {
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName must not be null");
    }

    @Override
    public String key(String cacheName, Object... parts) {
        Objects.requireNonNull(cacheName, "cacheName must not be null");
        StringBuilder key = new StringBuilder(applicationName).append(SEPARATOR).append(cacheName);
        if (parts != null) {
            for (Object part : parts) {
                key.append(SEPARATOR).append(String.valueOf(part));
            }
        }
        return key.toString();
    }
}
