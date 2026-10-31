package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Writes an RFC-9457 problem body for requests without acceptable credentials (401): anonymous
 * requests to protected resources AND rejected bearer tokens (malformed, expired, untrusted key). The
 * {@code WWW-Authenticate: Bearer} challenge is sent without an error description, so the reason a
 * token was rejected is never disclosed.
 */
public final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String typeBaseUri;

    public ProblemDetailAuthenticationEntryPoint(String typeBaseUri) {
        this.typeBaseUri = typeBaseUri;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        ProblemResponses.write(request, response, typeBaseUri, 401, ProblemResponses.UNAUTHENTICATED, "Unauthorized",
                "Full authentication is required to access this resource");
    }
}
