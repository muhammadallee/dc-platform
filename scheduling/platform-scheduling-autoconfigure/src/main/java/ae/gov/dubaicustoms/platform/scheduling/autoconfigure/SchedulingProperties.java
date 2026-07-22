package ae.gov.dubaicustoms.platform.scheduling.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the scheduling capability. Bound from {@code dc.platform.scheduling.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformSchedulingAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.scheduling")
public record SchedulingProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled) {
}
