package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes an RFC-9457 problem body for authenticated principals lacking authority (403). */
public final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final String typeBaseUri;

    public ProblemDetailAccessDeniedHandler(String typeBaseUri) {
        this.typeBaseUri = typeBaseUri;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemResponses.write(request, response, typeBaseUri, 403, ProblemResponses.ACCESS_DENIED, "Forbidden",
                "The current principal lacks the required authority");
    }
}
