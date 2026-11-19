package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.ErrorsProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;

// Shared ProblemDetail assembly for the platform advice beans and the error controller: one place sets type/title/
// detail/instance and the code/traceId/timestamp/retryable extensions, and wraps the body as the ErrorResponse that
// Spring's ErrorResponse.Interceptors receive, with the originating exception as its cause (D138).
public final class ProblemDetailFactory {

    /** Generic body detail for every 5xx: server-side messages routinely carry internals. */
    public static final String GENERIC_SERVER_ERROR_DETAIL = "An unexpected error occurred.";

    /** The {@code ProblemDetail} property holding the stable error code. */
    public static final String CODE = "code";

    // Statuses a client may retry the identical request on (after backing off).
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private final ErrorsProperties properties;
    private final TraceIdSource traceIds;
    private final Clock clock;

    public ProblemDetailFactory(ErrorsProperties properties, TraceIdSource traceIds, Clock clock) {
        this.properties = properties;
        this.traceIds = traceIds;
        this.clock = clock;
    }

    /**
     * Builds a fully populated problem body. Timestamp and trace extensions are ISO-8601 and hex strings, not temporal
     * objects, so the JSON shape does not depend on the application's Jackson modules.
     */
    public ProblemDetail create(int status, String code, String detail, String instancePath, Throwable source,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setDetail(status >= 500 ? GENERIC_SERVER_ERROR_DETAIL : detail);
        if (instancePath != null) {
            problem.setInstance(URI.create(instancePath));
        }
        return decorate(problem, code, source, request);
    }

    /**
     * Adds the platform contract to a body built elsewhere (by the service's {@code ErrorResponseException}, or by
     * Spring MVC for a 404, 405 or unreadable body):
     * <ul>
     *   <li>{@code code}: a {@code String} the thrower set is kept; anything else is replaced by {@code defaultCode};</li>
     *   <li>{@code type} and {@code title} from the code, unless the thrower set its own {@code type};</li>
     *   <li>{@code traceId}, {@code timestamp}, {@code retryable}; a 5xx detail becomes the generic text.</li>
     * </ul>
     */
    public ProblemDetail decorate(ProblemDetail problem, String defaultCode, Throwable source, HttpServletRequest request) {
        String code = serviceCode(problem);
        if (code == null) {
            code = defaultCode;
        }
        if (problem.getType() == null || ABOUT_BLANK.equals(problem.getType())) {
            problem.setType(URI.create(properties.typeBaseUri() + code));
            problem.setTitle(code);
        }
        if (problem.getStatus() >= 500) {
            problem.setDetail(GENERIC_SERVER_ERROR_DETAIL);
        }
        if (problem.getInstance() == null && request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        problem.setProperty(CODE, code);
        String traceId = traceIds.traceId(request);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        problem.setProperty("timestamp", Instant.now(clock).toString());
        problem.setProperty("retryable", RETRYABLE_STATUSES.contains(problem.getStatus()));
        if (properties.includeStacktrace() && source != null) {
            problem.setProperty("stacktrace", stackTraceOf(source));
        }
        return problem;
    }

    /**
     * Wraps a finished body as the {@link ErrorResponse} the advice returns: Spring MVC writes it and hands it to every
     * {@code ErrorResponse.Interceptor}, whose {@code getCause()} is then the originating exception.
     *
     * @param problem the finished body
     * @param headers response headers to carry (for example {@code Allow} on a 405); may be {@code null}
     * @param source the originating exception; may be {@code null} (a bare {@code sendError})
     * @return the error response
     */
    public static ErrorResponseException wrap(ProblemDetail problem, HttpHeaders headers, Throwable source) {
        PlatformErrorResponse response = new PlatformErrorResponse(problem, source);
        if (headers != null) {
            response.getHeaders().addAll(headers);
        }
        return response;
    }

    /**
     * Copies a body: status, type, title, detail, instance and every property (the property map is copied; its values
     * are shared). The advice decorates the copy, never the {@code ProblemDetail} an exception holds, because an
     * exception instance can be thrown more than once and by concurrent requests.
     *
     * @param source the body to copy
     * @return an independent body with the same content
     */
    public static ProblemDetail copyOf(ProblemDetail source) {
        ProblemDetail copy = ProblemDetail.forStatus(source.getStatus());
        copy.setType(source.getType());
        copy.setTitle(source.getTitle());
        copy.setDetail(source.getDetail());
        copy.setInstance(source.getInstance());
        if (source.getProperties() != null) {
            source.getProperties().forEach(copy::setProperty);
        }
        return copy;
    }

    /**
     * Returns the code a thrower set on the body, or {@code null}.
     *
     * @param problem the body
     * @return the {@code code} property when it is a non-blank {@code String}
     */
    public static String serviceCode(ProblemDetail problem) {
        Map<String, Object> extensions = problem.getProperties();
        Object code = extensions == null ? null : extensions.get(CODE);
        return code instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * Adds {@code rejectedValue} to a validation-error entry only when
     * {@code dc.platform.errors.include-rejected-values=true}: rejected values echo raw user input,
     * which may be personal data. Even then, credential-shaped fields are redacted.
     */
    public void putRejectedValue(Map<String, Object> entry, String fieldName, Object rejectedValue) {
        if (properties.includeRejectedValues()) {
            entry.put("rejectedValue", redact(fieldName, rejectedValue));
        }
    }

    /**
     * Redacts rejected values for credential-shaped fields: for password/secret/token fields the
     * input must never round-trip through an error response (or the logs that capture it).
     */
    public Object redact(String fieldName, Object rejectedValue) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")) {
            return "REDACTED";
        }
        return rejectedValue;
    }

    private static String stackTraceOf(Throwable source) {
        StringWriter buffer = new StringWriter();
        source.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    // The ErrorResponse the platform hands to Spring. Its message never includes the body (Spring's default message
    // does), so a log line about it cannot leak a detail; no stack trace of its own.
    private static final class PlatformErrorResponse extends ErrorResponseException {

        private PlatformErrorResponse(ProblemDetail problem, Throwable source) {
            super(HttpStatusCode.valueOf(problem.getStatus()), problem, source);
        }

        @Override
        public String getMessage() {
            return "platform error response, status " + getStatusCode().value();
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
