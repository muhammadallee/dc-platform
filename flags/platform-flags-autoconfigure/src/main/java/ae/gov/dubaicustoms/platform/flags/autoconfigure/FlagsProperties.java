package ae.gov.dubaicustoms.platform.flags.autoconfigure;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the feature-flags capability. Bound from {@code dc.platform.flags.*}.
 *
 * <p>Immutable; validated at startup. Registered via {@code @EnableConfigurationProperties} (never
 * scanned). The provider is chosen by what is on the classpath (OpenFeature over in-memory), not by a
 * property; this record carries the kill switch and the in-memory provider's seed flags.
 *
 * @param enabled master kill switch for the whole capability
 * @param staticFlags seed flags for the in-memory provider, from {@code dc.platform.flags.static.*}
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.flags")
public record FlagsProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Seed flags for the in-memory provider (key → value); bound from dc.platform.flags.static.*. */
        @Name("static") @DefaultValue Map<String, String> staticFlags) {
}
