package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the logging capability. Bound from {@code dc.platform.logging.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code LoggingAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). The format and service-name keys are
 * also read pre-context by {@code PlatformLoggingEnvironmentPostProcessor} — logging must be
 * configured before the application context exists.
 *
 * @param enabled master kill switch for the whole capability
 * @param format log output format; {@code json} in every deployed environment, {@code console}
 *        for humans (auto-selected when the {@code local} profile is active)
 * @param includeMdc copy MDC entries (traceId, spanId, …) into JSON log fields
 * @param serviceName service name stamped on every JSON log line; defaults to
 *        {@code spring.application.name}
 * @param mdc which MDC entries reach JSON log fields
 * @param redaction masking of credentials and PII in JSON log output
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.logging")
public record LoggingProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Output format: json (deployed) or console (humans; auto-picked on the local profile). */
        @DefaultValue("json") Format format,
        /** Copy MDC entries (traceId, spanId, ...) into JSON log fields. */
        @DefaultValue("true") boolean includeMdc,
        /** Service name stamped on every JSON line; defaults to spring.application.name. */
        @DefaultValue("") String serviceName,
        /** Which MDC entries reach JSON log fields. */
        @DefaultValue Mdc mdc,
        /** Masking of credentials and PII in JSON log output. */
        @DefaultValue Redaction redaction) {

    /**
     * MDC field selection.
     *
     * @param include MDC keys written besides {@code traceId} and {@code spanId}; empty (the default)
     *        writes every MDC key, a non-empty list switches to strict mode and drops unlisted keys
     * @since 0.1.0
     */
    public record Mdc(
            /** Strict mode: MDC keys written besides traceId/spanId; empty writes every key. */
            @DefaultValue List<String> include) {
    }

    /**
     * Redaction of JSON log output. Credentials (JWTs, Bearer/Basic credentials, values of
     * credential-named keys) and the default PII set (Emirates ID, Luhn-valid card numbers) are
     * replaced by {@code ****}.
     *
     * @param enabled mask values (on by default)
     * @param patterns additional regular expressions whose matches are masked
     * @since 0.1.0
     */
    public record Redaction(
            /** Mask credentials and PII in JSON log output. */
            @DefaultValue("true") boolean enabled,
            /** Additional regular expressions whose matches are masked. */
            @DefaultValue List<String> patterns) {
    }

    /**
     * Log output format.
     *
     * @since 0.1.0
     */
    public enum Format {
        /** Structured JSON lines (logstash encoder, ECS-ish field names). */
        JSON,
        /** Boot's default human-readable console output. */
        CONSOLE
    }
}
