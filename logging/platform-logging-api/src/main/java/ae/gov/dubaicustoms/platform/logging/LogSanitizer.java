package ae.gov.dubaicustoms.platform.logging;

/**
 * SPI-lite: scrub a value before it is written to a log field. Contribute beans of this type;
 * platform log-enrichment components apply them in {@code @Order} to MDC and structured-argument
 * values.
 *
 * <pre>{@code
 * @Bean
 * LogSanitizer emiratesIdSanitizer() {
 *     return (key, value) -> "emiratesId".equals(key) ? "784-****" : value;
 * }
 * }</pre>
 *
 * <p><b>Implementation requirements:</b> implementations must be thread-safe, fast (they sit on
 * the logging hot path), and total — return the value unchanged when the key is not theirs,
 * never {@code null} for a non-null input, and never throw: a throwing sanitizer would suppress
 * the very log line that might explain an incident. The platform guarantees {@code key} is
 * non-null. New {@code default} methods may be added in minor releases.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface LogSanitizer {

    /**
     * Returns the value to actually log for the given key.
     *
     * @param key the field or MDC key; never {@code null}
     * @param value the candidate value; may be {@code null}
     * @return the sanitized value; {@code null} only if {@code value} was {@code null}
     */
    String sanitize(String key, String value);
}
