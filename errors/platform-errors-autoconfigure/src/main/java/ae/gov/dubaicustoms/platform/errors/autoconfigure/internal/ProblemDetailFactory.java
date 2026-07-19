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
import org.springframework.http.ProblemDetail;

// Shared ProblemDetail assembly for the platform advice beans: one place sets type/title/detail/
// instance and the code/correlationId/timestamp extensions, and applies customizers in order.
public final class ProblemDetailFactory {

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
     * Timestamp/correlation extensions are ISO-8601 strings, not temporal objects, so the JSON
     * shape does not depend on which Jackson generation/modules the application runs.
     */
    public ProblemDetail create(int status, ErrorCode code, String detail, String instancePath, Throwable source) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(properties.typeBaseUri() + code.value()));
        problem.setTitle(code.value());
        problem.setDetail(detail);
        if (instancePath != null) {
            problem.setInstance(URI.create(instancePath));
        }
        problem.setProperty("code", code.value());
        RequestContext.correlationId().map(CorrelationId::value)
                .ifPresent(id -> problem.setProperty("correlationId", id));
        problem.setProperty("timestamp", Instant.now(clock).toString());
        if (properties.includeStacktrace()) {
            problem.setProperty("stacktrace", stackTraceOf(source));
        }
        customizers.forEach(customizer -> customizer.customize(problem, source));
        return problem;
    }

    /**
     * Redacts rejected values for credential-shaped fields: rejected values echo raw user input,
     * and for password/secret/token fields that input must never round-trip through an error
     * response (or the logs that capture it).
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
