/**
 * The cache capability contract: {@link ae.gov.dubaicustoms.platform.cache.CacheKeyConvention}
 * composes a cache key from parts (the platform default prefixes with the application name so keys
 * from different services never collide in a shared cache), and
 * {@link ae.gov.dubaicustoms.platform.cache.CacheNames} captures cache-name conventions.
 *
 * <p>The programming model is Spring's own {@code @Cacheable}/{@code @CacheEvict} — the platform
 * ships no custom cache annotation. {@code platform-cache-autoconfigure} supplies the default
 * {@code CacheKeyConvention} and decorates the active {@code CacheManager}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.cache;
