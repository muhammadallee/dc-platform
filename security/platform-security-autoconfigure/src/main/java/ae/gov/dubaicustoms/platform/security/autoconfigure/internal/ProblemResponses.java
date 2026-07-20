package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Shared RFC-9457 problem-body writer for the security filter chain's entry point and denied
 * handler, which run outside the DispatcherServlet and so cannot reach the errors capability's
 * {@code @RestControllerAdvice}.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"status":%d,"title":"%s","detail":"%s"}""".formatted(status, escape(title), escape(detail)));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
