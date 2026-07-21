/**
 * Auto-configuration for the cache capability: a default
 * {@link ae.gov.dubaicustoms.platform.cache.CacheKeyConvention}, per-cache TTL/size policy, and a
 * Caffeine or Redis {@code CacheManager} depending on what is on the classpath.
 *
 * <p>Entry point {@link ae.gov.dubaicustoms.platform.cache.autoconfigure.PlatformCacheAutoConfiguration};
 * bound properties in {@link ae.gov.dubaicustoms.platform.cache.autoconfigure.CacheProperties}
 * ({@code dc.platform.cache.*}). Implementation details live under {@code .internal}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.cache.autoconfigure;
