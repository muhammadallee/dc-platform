package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.errors.ProblemDetailCustomizer;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.ErrorsProperties;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ProblemDetail;

// Shared ProblemDetail assembly for the platform advice beans: one place sets type/title/detail/
// instance and the code/traceId/timestamp/retryable extensions, and applies customizers in order.
public final class ProblemDetailFactory {

    /** Generic body detail for every 5xx: server-side messages routinely carry internals. */
    public static final String GENERIC_SERVER_ERROR_DETAIL = "An unexpected error occurred.";

    // Statuses a client may retry the identical request on (after backing off).
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    private final ErrorsProperties properties;
    private final List<ProblemDetailCustomizer> customizers;
    private final Clock clock;

    public ProblemDetailFactory(ErrorsProperties properties, List<ProblemDetailCustomizer> customizers, Clock clock) {
        this.properties = properties;
        this.customizers = List.copyOf(customizers);
        this.clock = clock;
    }

    /**
     * Builds the fully populated problem body and applies the customizers in order.
     * Timestamp/trace extensions are ISO-8601 / hex strings, not temporal objects, so the JSON
     * shape does not depend on which Jackson generation/modules the application runs.
     */
    public ProblemDetail create(int status, ErrorCode code, String detail, String instancePath, Throwable source) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setDetail(status >= 500 ? GENERIC_SERVER_ERROR_DETAIL : detail);
        if (instancePath != null) {
            problem.setInstance(URI.create(instancePath));
        }
        return decorate(problem, code, source);
    }

    /**
     * Adds the platform contract to a problem body built elsewhere (e.g. by Spring MVC for a 404,
     * 405 or unreadable body): type/title from the code, plus {@code code}, {@code traceId},
     * {@code timestamp} and {@code retryable}. A 5xx body's detail is replaced by the generic text.
     */
    public ProblemDetail decorate(ProblemDetail problem, ErrorCode code, Throwable source) {
        problem.setType(URI.create(properties.typeBaseUri() + code.value()));
        problem.setTitle(code.value());
        if (problem.getStatus() >= 500) {
            problem.setDetail(GENERIC_SERVER_ERROR_DETAIL);
        }
        problem.setProperty("code", code.value());
        RequestContext.correlationId().map(CorrelationId::value)
                .ifPresent(id -> problem.setProperty("traceId", id));
        problem.setProperty("timestamp", Instant.now(clock).toString());
        problem.setProperty("retryable", RETRYABLE_STATUSES.contains(problem.getStatus()));
        if (properties.includeStacktrace() && source != null) {
            problem.setProperty("stacktrace", stackTraceOf(source));
        }
        customizers.forEach(customizer -> customizer.customize(problem, source));
        return problem;
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
}
