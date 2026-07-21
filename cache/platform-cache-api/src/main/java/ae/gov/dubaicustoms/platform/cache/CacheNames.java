package ae.gov.dubaicustoms.platform.cache;

import ae.gov.dubaicustoms.platform.core.PlatformApi;

/**
 * Cache-name conventions. A cache name is a dot-separated path of lowercase segments naming what is
 * cached ({@code "orders.by-id"}, {@code "customer.summary"}); {@link #of(String...)} builds one and
 * validates the segments.
 *
 * <p>Cache <em>names</em> are distinct from cache <em>keys</em>: the name identifies a logical cache
 * (and its TTL/size policy under {@code dc.platform.cache.caches.<name>}), while
 * {@link CacheKeyConvention} composes the per-entry key within a cache.
 *
 * <p>Utility holder; not instantiable. Thread-safe.
 *
 * @since 0.2.0
 */
@PlatformApi
public final class CacheNames {

    /** Separator between the segments of a cache name. */
    public static final String SEGMENT_SEPARATOR = ".";

    /**
     * Builds a cache name from segments, joining them with {@link #SEGMENT_SEPARATOR}.
     *
     * @param segments the ordered name segments; at least one, each non-blank and free of the
     *        separator
     * @return the composed cache name; never {@code null}
     * @throws IllegalArgumentException if no segments are given, or any segment is blank or contains
     *         the separator
     */
    public static String of(String... segments) {
        if (segments == null || segments.length == 0) {
            throw new IllegalArgumentException("a cache name needs at least one segment");
        }
        StringBuilder name = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("cache-name segments must be non-blank");
            }
            if (segment.contains(SEGMENT_SEPARATOR)) {
                throw new IllegalArgumentException(
                        "cache-name segment '" + segment + "' must not contain '" + SEGMENT_SEPARATOR + "'");
            }
            if (name.length() > 0) {
                name.append(SEGMENT_SEPARATOR);
            }
            name.append(segment);
        }
        return name.toString();
    }

    private CacheNames() {
    }
}
