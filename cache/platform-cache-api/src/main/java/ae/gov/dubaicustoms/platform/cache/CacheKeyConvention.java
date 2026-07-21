package ae.gov.dubaicustoms.platform.cache;

import ae.gov.dubaicustoms.platform.core.PlatformApi;

/**
 * Composes a cache key from a cache name and a set of parts, applying the platform's key convention.
 *
 * <p>The platform default joins the parts with {@code ':'} and prefixes the application name, so the
 * same logical key computed by two different services does not collide in a shared cache backend
 * (Redis). Inject it where a key must be computed outside Spring's {@code @Cacheable} SpEL:
 *
 * <pre>{@code
 * String key = convention.key("orders", customerId, "summary");
 * // e.g. "orders-service:orders:42:summary"
 * }</pre>
 *
 * <p>Implementations are thread-safe and stateless; the returned key is never {@code null}.
 *
 * @since 0.2.0
 */
@PlatformApi
public interface CacheKeyConvention {

    /**
     * Composes the cache key for the given cache and parts.
     *
     * @param cacheName the logical cache name; never {@code null}
     * @param parts the key parts, in order; each is rendered via {@link String#valueOf(Object)}
     * @return the composed cache key; never {@code null}
     */
    String key(String cacheName, Object... parts);
}
