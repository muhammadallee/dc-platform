package ae.gov.dubaicustoms.platform.validation.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the validation capability. Bound from {@code dc.platform.validation.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformValidationAutoConfiguration}
 * via {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.validation")
public record ValidationProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled) {
}
