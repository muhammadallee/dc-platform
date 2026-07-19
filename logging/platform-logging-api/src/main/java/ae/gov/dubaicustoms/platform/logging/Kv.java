package ae.gov.dubaicustoms.platform.logging;

import java.util.Objects;

/**
 * A key-value pair for structured log statements: renders as {@code key=value} in pattern
 * layouts, and the platform JSON encoder lifts MDC and argument values into fields.
 *
 * <pre>{@code
 * log.info("order accepted {}", Kv.of("orderId", order.id()));
 * }</pre>
 *
 * <p>Thin by design — no logstash types: in console format it is just a readable
 * {@code toString()}; richer JSON mapping stays an encoder concern.
 *
 * <p>Value object; immutable and thread-safe. The key is never {@code null}; the value may be
 * {@code null} and renders as the literal {@code null}.
 *
 * @param key the field name; never {@code null}
 * @param value the field value; may be {@code null}
 * @since 0.1.0
 */
public record Kv(String key, Object value) {

    /**
     * Validates the key.
     *
     * @throws NullPointerException if {@code key} is null
     */
    public Kv {
        Objects.requireNonNull(key, "key must not be null");
    }

    /**
     * Creates a pair.
     *
     * @param key the field name; never {@code null}
     * @param value the field value; may be {@code null}
     * @return the pair; never {@code null}
     * @throws NullPointerException if {@code key} is null
     */
    public static Kv of(String key, Object value) {
        return new Kv(key, value);
    }

    /** Renders {@code key=value} so pattern layouts stay readable without JSON. */
    @Override
    public String toString() {
        return key + "=" + value;
    }
}
