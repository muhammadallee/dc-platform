package ae.gov.dubaicustoms.platform.core.context;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apiguardian.api.API;

/**
 * Correlation identifier propagated across threads, HTTP, and messaging.
 *
 * <p>Format is 32 lowercase hex characters (a UUID without dashes), so it round-trips through
 * HTTP headers and message properties without escaping.
 *
 * <pre>{@code
 * CorrelationId id = CorrelationId.random();
 * httpHeaders.set("X-Correlation-Id", id.value());
 * }</pre>
 *
 * <p>Value object; immutable and thread-safe; creation is cheap. The component is never
 * {@code null}.
 *
 * @param value the identifier text; must match {@code ^[0-9a-f]{32}$}
 * @since 0.1.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public record CorrelationId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{32}$");

    /**
     * Validates the identifier format.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is not 32 lowercase hex characters
     */
    public CorrelationId {
        Objects.requireNonNull(value, "value must not be null");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "correlation id '" + value + "' must be 32 lowercase hex characters (UUID without dashes)");
        }
    }

    /**
     * Creates a new random correlation id.
     *
     * @return a fresh identifier; never {@code null}
     */
    public static CorrelationId random() {
        UUID uuid = UUID.randomUUID();
        return new CorrelationId(uuid.toString().replace("-", ""));
    }
}
