package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes an RFC-9457 problem body for authenticated requests lacking the required authority (403). */
public final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemResponses.write(response, 403, "Forbidden", "The current principal lacks the required authority");
    }
}
