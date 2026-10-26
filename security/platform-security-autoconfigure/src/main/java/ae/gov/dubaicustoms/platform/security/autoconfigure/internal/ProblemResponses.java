package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;

/**
 * Shared RFC-9457 problem-body writer for the security filter chain's entry point and denied
 * handler, which run outside the DispatcherServlet and so cannot reach the errors capability's
 * {@code @RestControllerAdvice}. Emits the same platform extensions as the errors capability
 * ({@code code}, {@code traceId}, {@code timestamp}, {@code retryable}); the reason a request was
 * rejected is never included.
 */
final class ProblemResponses {

    /** Missing, expired or otherwise unacceptable credentials. */
    static final ErrorCode UNAUTHENTICATED = new ErrorCode("DC-SEC-0401");

    /** Authenticated principal without the required authority. */
    static final ErrorCode ACCESS_DENIED = new ErrorCode("DC-SEC-0403");

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, int status, ErrorCode code, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        StringBuilder body = new StringBuilder()
                .append("{\"status\":").append(status)
                .append(",\"title\":\"").append(escape(title))
                .append("\",\"detail\":\"").append(escape(detail))
                .append("\",\"code\":\"").append(code.value()).append('"');
        RequestContext.correlationId().map(CorrelationId::value)
                .ifPresent(traceId -> body.append(",\"traceId\":\"").append(traceId).append('"'));
        body.append(",\"timestamp\":\"").append(Instant.now()).append('"')
                .append(",\"retryable\":false}");
        response.getWriter().write(body.toString());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
