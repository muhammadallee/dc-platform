package ae.gov.dubaicustoms.platform.restclient;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown when a platform-conventional REST call receives a non-2xx response.
 *
 * <p>Carries the response status, a body snippet truncated to 1KB (never the full payload, so
 * exception messages and logs stay bounded), and the remote correlation id echoed by the callee,
 * when present.
 *
 * <pre>{@code
 * try {
 *     ordersClient.get().uri("/orders/{id}", id).retrieve().body(Order.class);
 * } catch (RemoteCallException e) {
 *     log.warn("orders call failed: status={} body={}", e.status(), e.bodySnippet());
 * }
 * }</pre>
 *
 * <p>Immutable and thread-safe; the body snippet is already bounded, so it is safe to log
 * directly. {@code clientName} and {@code body} are never {@code null}.
 *
 * @since 0.2.0
 */
public final class RemoteCallException extends PlatformException {

    private static final ErrorCode CODE = new ErrorCode("DC-RCLIENT-0500");
    private static final int MAX_BODY_SNIPPET_LENGTH = 1024;

    private final int status;
    private final String bodySnippet;
    private final String remoteCorrelationId;

    /**
     * Creates the exception for a failed remote call.
     *
     * @param clientName the {@link PlatformRestClientFactory} client name that made the call; never {@code null}
     * @param status the HTTP status code returned by the remote service
     * @param body the raw response body; never {@code null}, truncated to 1KB before storage
     * @param remoteCorrelationId the correlation id echoed by the callee, or {@code null} if absent
     * @param cause the underlying HTTP client exception, or {@code null}
     * @throws NullPointerException if {@code clientName} or {@code body} is null
     */
    public RemoteCallException(String clientName, int status, String body, String remoteCorrelationId, Throwable cause) {
        super(CODE, message(Objects.requireNonNull(clientName, "clientName must not be null"), status), cause);
        Objects.requireNonNull(body, "body must not be null");
        this.status = status;
        this.bodySnippet = body.length() > MAX_BODY_SNIPPET_LENGTH ? body.substring(0, MAX_BODY_SNIPPET_LENGTH) : body;
        this.remoteCorrelationId = remoteCorrelationId;
    }

    private static String message(String clientName, int status) {
        return "REST call '" + clientName + "' failed with status " + status;
    }

    /**
     * Returns the HTTP status code returned by the remote service.
     *
     * @return the status code
     */
    public int status() {
        return status;
    }

    /**
     * Returns the response body, truncated to 1KB.
     *
     * @return the truncated body; never {@code null}, may be empty
     */
    public String bodySnippet() {
        return bodySnippet;
    }

    /**
     * Returns the correlation id echoed by the callee, if any.
     *
     * @return the remote correlation id; empty if the callee did not echo one
     */
    public Optional<String> remoteCorrelationId() {
        return Optional.ofNullable(remoteCorrelationId);
    }
}
