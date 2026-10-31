package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;

/**
 * Shared RFC-9457 problem-body writer for the security filter chain's entry point and denied
 * handler, which run outside the DispatcherServlet and so cannot reach the errors capability's
 * {@code @RestControllerAdvice}. Emits the same contract as the errors capability: {@code type}
 * (the configured problem-type base URI + code), {@code title}, {@code status}, {@code detail},
 * {@code instance}, {@code code}, {@code traceId}, {@code timestamp}, {@code retryable}. The reason a
 * request was rejected is never included.
 */
final class ProblemResponses {

    /** Missing, expired or otherwise unacceptable credentials. */
    static final ErrorCode UNAUTHENTICATED = new ErrorCode("DC-SEC-0401");

    /** Authenticated principal without the required authority. */
    static final ErrorCode ACCESS_DENIED = new ErrorCode("DC-SEC-0403");

    private ProblemResponses() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, String typeBaseUri, int status,
            ErrorCode code, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        StringBuilder body = new StringBuilder()
                .append("{\"type\":\"").append(escape(typeBaseUri + code.value()))
                .append("\",\"title\":\"").append(escape(title))
                .append("\",\"status\":").append(status)
                .append(",\"detail\":\"").append(escape(detail))
                .append("\",\"instance\":\"").append(escape(request.getRequestURI()))
                .append("\",\"code\":\"").append(code.value()).append('"');
        RequestContext.correlationId().map(CorrelationId::value)
                .ifPresent(traceId -> body.append(",\"traceId\":\"").append(traceId).append('"'));
        body.append(",\"timestamp\":\"").append(Instant.now()).append('"')
                .append(",\"retryable\":false}");
        response.getWriter().write(body.toString());
    }

    // JSON string escaping for the characters a URI path or configured text can contain.
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
