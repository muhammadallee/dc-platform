package ae.gov.dubaicustoms.platform.cache.autoconfigure;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the cache capability. Bound from {@code dc.platform.cache.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformCacheAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param caches per-cache TTL/size policy, keyed by cache name (as used in {@code @Cacheable})
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.cache")
public record CacheProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Per-cache TTL/size policy, keyed by cache name. */
        Map<String, CacheSpec> caches) {

    /** Normalizes an unset {@code caches} map to empty (Spring binds it {@code null}). */
    public CacheProperties {
        caches = caches == null ? Map.of() : Map.copyOf(caches);
    }

    /**
     * TTL and maximum size for a single named cache; either component may be {@code null} to use the
     * provider default (unbounded / no expiry).
     *
     * @param ttl time-to-live after write, or {@code null} for no expiry
     * @param maxSize maximum entry count, or {@code null} for unbounded
     * @since 0.2.0
     */
    public record CacheSpec(Duration ttl, Long maxSize) {
    }
}
