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
 * <p>The former {@code dc.platform.core.correlation.*} settings are gone: the correlation id is the
 * W3C trace ID and travels in {@code traceparent}, so there is no header name to choose and nothing to
 * generate. The keys remain in the configuration metadata as deprecated (level {@code error}).
 *
 * @param enabled master kill switch for the whole capability
 * @param bannerEnabled whether the startup capability banner is logged
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.core")
public record CoreProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Log the one-line capability banner at startup. */
        @DefaultValue("true") boolean bannerEnabled) {
}
