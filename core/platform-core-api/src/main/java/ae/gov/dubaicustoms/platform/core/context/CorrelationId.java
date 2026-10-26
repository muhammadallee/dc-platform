package ae.gov.dubaicustoms.platform.core.context;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apiguardian.api.API;

/**
 * Correlation identifier propagated across threads, HTTP, and messaging.
 *
 * <p>Since the hardening of the core capability, the platform's only correlation identifier is the
 * W3C trace ID of the current span: the platform populates {@link RequestContext} with it, and it
 * travels between services in the standard {@code traceparent} header (never in a custom header).
 * The format, 32 lowercase hex characters, is exactly the W3C trace-id format.
 *
 * <pre>{@code
 * RequestContext.correlationId()
 *         .map(CorrelationId::value)   // == the current trace ID
 *         .ifPresent(traceId -> problem.setProperty("traceId", traceId));
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
            // The rejected value is deliberately not echoed: it often comes straight from a request
            // header, and exception messages end up in logs (header/log injection).
            throw new IllegalArgumentException(
                    "correlation id must be 32 lowercase hex characters (got " + value.length() + " characters)");
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
