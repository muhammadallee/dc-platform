package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the errors capability. Bound from {@code dc.platform.errors.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformErrorHandlingAutoConfiguration}
 * via {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param includeStacktrace add a {@code stacktrace} extension to problem bodies (debugging aid;
 *        never enable in production)
 * @param typeBaseUri base URI prepended to the error code to form the problem {@code type}
 * @param mapValidation map Bean Validation failures to 400 problem responses with {@code errors[]}
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.errors")
public record ErrorsProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Add a stacktrace extension to problem bodies (debugging aid; keep off in production). */
        @DefaultValue("false") boolean includeStacktrace,
        /** Base URI prepended to the error code to form the problem type. */
        @DefaultValue("https://errors.dc.com/") String typeBaseUri,
        /** Map Bean Validation failures to 400 problem responses with an errors[] extension. */
        @DefaultValue("true") boolean mapValidation) {
}
