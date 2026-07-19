package ae.gov.dubaicustoms.platform.core.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the core capability. Bound from {@code dc.platform.core.*}.
 *
 * <p>Immutable; validated at startup. Registered by the core auto-configurations via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param bannerEnabled whether the startup capability banner is logged
 * @param correlation correlation-id filter settings
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.core")
public record CoreProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Log the one-line capability banner at startup. */
        @DefaultValue("true") boolean bannerEnabled,
        /** Correlation-id filter settings. */
        @DefaultValue Correlation correlation) {

    /**
     * Correlation-id propagation settings.
     *
     * @param headerName HTTP header carrying the correlation id
     * @param generateIfMissing generate a fresh id when the incoming request carries none
     * @since 0.1.0
     */
    public record Correlation(
            /** HTTP header carrying the correlation id. */
            @DefaultValue("X-Correlation-Id") String headerName,
            /** Generate a fresh id when the incoming request carries none (or an invalid one). */
            @DefaultValue("true") boolean generateIfMissing) {
    }
}
