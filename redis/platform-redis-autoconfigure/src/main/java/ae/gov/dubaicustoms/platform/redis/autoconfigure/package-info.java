/**
 * Auto-configuration for direct Redis client conventions (distinct from the cache capability):
 * namespaces {@code StringRedisTemplate} keys with a per-service prefix.
 *
 * <p>Entry point {@link ae.gov.dubaicustoms.platform.redis.autoconfigure.PlatformRedisAutoConfiguration};
 * bound properties in {@link ae.gov.dubaicustoms.platform.redis.autoconfigure.PlatformRedisProperties}
 * ({@code dc.platform.redis.*}). Implementation details live under {@code .internal}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.redis.autoconfigure;
