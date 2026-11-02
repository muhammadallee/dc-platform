package ae.gov.dubaicustoms.platform.restclient;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apiguardian.api.API;

/**
 * Thrown when a platform-conventional REST call fails, in any way: a non-2xx response, a timeout, a lost
 * connection, an exhausted deadline, or a local refusal by the breaker or bulkhead. {@link #kind()} says which.
 *
 * <p>Carries the response status when there was one, and a body snippet truncated to 1KB with bearer tokens, JWTs and
 * the values of secret-named keys removed. Redaction works by pattern, so a body can still hold personal or business
 * data: log the snippet only where that data may go.
 *
 * <pre>{@code
 * try {
 *     return ordersClient.get().uri("/orders/{id}", id).retrieve().body(Order.class);
 * } catch (RemoteCallException e) {
 *     if (e.kind() == RemoteCallException.Kind.CIRCUIT_OPEN) {
 *         return cachedOrder(id); // a fallback is the caller's choice
 *     }
 *     throw e; // reaches the caller as a ProblemDetail with the right status and retryable flag
 * }
 * }</pre>
 *
 * <p>Immutable and thread-safe. {@code clientName} and {@code body} are never {@code null}.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class RemoteCallException extends PlatformException {

    /**
     * What went wrong.
     *
     * @since 1.0.0
     */
    @API(status = API.Status.STABLE, since = "1.0.0")
    public enum Kind {
        /** The remote answered with a non-2xx status. */
        RESPONSE,
        /** No complete answer within the read or connect timeout. */
        TIMEOUT,
        /** The connection failed or was lost. */
        CONNECTION,
        /** The client's overall deadline ran out. */
        DEADLINE,
        /** The client's circuit breaker refused the call. Nothing was sent. */
        CIRCUIT_OPEN,
        /** The client's bulkhead was full. Nothing was sent. */
        BULKHEAD_FULL
    }

    private static final ErrorCode CODE = new ErrorCode("DC-RCLIENT-0500");
    private static final int MAX_BODY_SNIPPET_LENGTH = 1024;
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(429, 502, 503, 504);
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)(\"?[a-z0-9_.-]*"
            + "(?:password|passwd|pwd|secret|token|api[-_]?key|authorization|credential|cookie)[a-z0-9_.-]*\"?\\s*[:=]\\s*)"
            + "(\"[^\"]*\"?|[^\\s,&;}]+)");

    private final Kind kind;
    private final int status;
    private final String bodySnippet;
    private final boolean repeatable;
    private final String remoteCorrelationId;

    /**
     * Creates the exception for a non-2xx response whose method is unknown, so a repeat is not assumed to be safe.
     *
     * @param clientName the {@link PlatformRestClientFactory} client name that made the call; never {@code null}
     * @param status the HTTP status code returned by the remote service
     * @param body the raw response body; never {@code null}, redacted and truncated to 1KB before storage
     * @param remoteCorrelationId the correlation id echoed by the callee, or {@code null} if absent
     * @param cause the underlying HTTP client exception, or {@code null}
     * @throws NullPointerException if {@code clientName} or {@code body} is null
     */
    public RemoteCallException(String clientName, int status, String body, String remoteCorrelationId, Throwable cause) {
        this(clientName, Kind.RESPONSE, status, body, false, remoteCorrelationId, cause);
    }

    /**
     * Creates the exception for any failed call.
     *
     * @param clientName the client name that made the call; never {@code null}
     * @param kind what went wrong; never {@code null}
     * @param status the HTTP status returned by the remote service, or 0 if there was no response
     * @param body the raw response body; never {@code null}, redacted and truncated to 1KB before storage
     * @param repeatable whether sending the call again could not apply it twice
     * @param cause the underlying failure, or {@code null}
     * @throws NullPointerException if {@code clientName}, {@code kind} or {@code body} is null
     * @since 1.0.0
     */
    public RemoteCallException(String clientName, Kind kind, int status, String body, boolean repeatable, Throwable cause) {
        this(clientName, kind, status, body, repeatable, null, cause);
    }

    private RemoteCallException(String clientName, Kind kind, int status, String body, boolean repeatable,
            String remoteCorrelationId, Throwable cause) {
        super(CODE, message(Objects.requireNonNull(clientName, "clientName must not be null"),
                Objects.requireNonNull(kind, "kind must not be null"), status), cause);
        this.kind = kind;
        this.status = status;
        this.bodySnippet = snippet(Objects.requireNonNull(body, "body must not be null"));
        this.repeatable = repeatable;
        this.remoteCorrelationId = remoteCorrelationId;
    }

    private static String message(String clientName, Kind kind, int status) {
        return kind == Kind.RESPONSE ? "REST call '" + clientName + "' failed with status " + status
                : "REST call '" + clientName + "' failed: " + kind;
    }

    private static String snippet(String body) {
        String redacted = SECRET_VALUE.matcher(JWT.matcher(BEARER.matcher(body).replaceAll("Bearer [REDACTED]"))
                .replaceAll("[REDACTED]")).replaceAll("$1[REDACTED]");
        if (redacted.length() <= MAX_BODY_SNIPPET_LENGTH) {
            return redacted;
        }
        int end = Character.isHighSurrogate(redacted.charAt(MAX_BODY_SNIPPET_LENGTH - 1)) ? MAX_BODY_SNIPPET_LENGTH - 1
                : MAX_BODY_SNIPPET_LENGTH;
        return redacted.substring(0, end);
    }

    /**
     * Returns what went wrong.
     *
     * @return the kind of failure; never {@code null}
     * @since 1.0.0
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the HTTP status code returned by the remote service.
     *
     * @return the status code, or 0 if there was no response
     */
    public int status() {
        return status;
    }

    /**
     * Returns the response body, redacted and truncated to 1KB.
     *
     * @return the snippet; never {@code null}, may be empty
     */
    public String bodySnippet() {
        return bodySnippet;
    }

    /**
     * Whether repeating the call later is both safe and might help. False when the outcome is unknown: a POST that
     * timed out may have been applied.
     *
     * @return true if the caller may repeat the call
     * @since 1.0.0
     */
    public boolean retryable() {
        return problemStatus() != 500;
    }

    /**
     * Returns the status for the ProblemDetail: 503 or 504 when the caller may try again, otherwise 500.
     *
     * @return the HTTP status
     * @since 1.0.0
     */
    @Override
    public int problemStatus() {
        if (!repeatable) {
            return 500;
        }
        return switch (kind) {
            case RESPONSE -> TRANSIENT_STATUSES.contains(status) ? 503 : 500;
            case DEADLINE -> 504;
            case TIMEOUT, CONNECTION, CIRCUIT_OPEN, BULKHEAD_FULL -> 503;
        };
    }

    /**
     * Returns the correlation id echoed by the callee, if any.
     *
     * @return the remote correlation id; empty if the callee did not echo one
     * @deprecated the platform no longer reads or echoes custom correlation headers: caller and
     *             callee share one W3C trace (propagated in {@code traceparent}), so the caller's own
     *             trace ID already joins both sides' logs. The platform client always passes
     *             {@code null}, so this is empty for platform-created exceptions.
     */
    @Deprecated(since = "1.0.0")
    public Optional<String> remoteCorrelationId() {
        return Optional.ofNullable(remoteCorrelationId);
    }
}
