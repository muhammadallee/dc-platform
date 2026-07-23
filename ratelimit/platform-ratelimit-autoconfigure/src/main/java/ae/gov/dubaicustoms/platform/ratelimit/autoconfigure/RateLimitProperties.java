package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the rate-limiting capability. Bound from {@code dc.platform.ratelimit.*}.
 *
 * <p>Immutable; validated at startup. Registered via {@code @EnableConfigurationProperties} (never
 * scanned). The provider is chosen by what is on the classpath (Redis over in-memory), not by a
 * property; this record carries the kill switch and the optional HTTP-filter settings.
 *
 * @param enabled master kill switch for the whole capability
 * @param http settings for the optional all-requests HTTP rate-limit filter (off by default)
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.ratelimit")
public record RateLimitProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Settings for the optional HTTP filter that rate-limits every request. */
        @DefaultValue Http http) {

    /**
     * Settings for the HTTP filter that applies one rate limit to all inbound requests.
     *
     * @param enabled whether the filter is installed (off by default — annotate methods instead)
     * @param keyBy whether requests are bucketed by caller identity or by client IP
     * @param permits the permits allowed per {@link #window()} per key
     * @param window the window length
     */
    public record Http(
            /** Whether the all-requests HTTP rate-limit filter is installed. */
            @DefaultValue("false") boolean enabled,
            /** The request dimension the limit is keyed by. */
            @DefaultValue("IP") KeyBy keyBy,
            /** Permits allowed per window, per key. */
            @DefaultValue("100") int permits,
            /** The window length. */
            @DefaultValue("PT1M") Duration window) {
    }

    /** How the HTTP filter derives the rate-limit key from a request. */
    public enum KeyBy {
        /** Bucket by the authenticated user (servlet remote user), falling back to {@code anonymous}. */
        USER,
        /** Bucket by the client IP address. */
        IP
    }
}
