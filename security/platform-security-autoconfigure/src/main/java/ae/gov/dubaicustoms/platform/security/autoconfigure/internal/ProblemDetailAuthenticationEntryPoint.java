package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Writes an RFC-9457 problem body for requests without acceptable credentials (401): anonymous
 * requests to protected resources AND rejected bearer tokens (malformed, expired, untrusted key). The
 * {@code WWW-Authenticate: Bearer} challenge is sent without an error description, so the reason a
 * token was rejected is never disclosed.
 */
public final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String typeBaseUri;
    private final SecurityMetrics metrics;

    public ProblemDetailAuthenticationEntryPoint(String typeBaseUri) {
        this(typeBaseUri, SecurityMetrics.NONE);
    }

    public ProblemDetailAuthenticationEntryPoint(String typeBaseUri, SecurityMetrics metrics) {
        this.typeBaseUri = typeBaseUri;
        this.metrics = metrics;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (authException instanceof OAuth2AuthenticationException) {
            count();
        }
        response.setHeader("WWW-Authenticate", "Bearer");
        ProblemResponses.write(request, response, typeBaseUri, 401, ProblemResponses.UNAUTHENTICATED, "Unauthorized",
                "Full authentication is required to access this resource");
    }

    /**
     * Writes the 503 for a token that cannot be checked because the signing keys cannot be fetched. Nothing reaches the
     * application, and the caller learns only to try again.
     *
     * @param request the request
     * @param response the response
     * @throws IOException if the body cannot be written
     */
    public void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemResponses.write(request, response, typeBaseUri, 503, ProblemResponses.UNAVAILABLE, "Service Unavailable",
                "Authentication is temporarily unavailable", true);
    }

    // A failing meter registry must not turn a 401 into a 500.
    private void count() {
        try {
            metrics.tokenRejected();
        } catch (RuntimeException ignored) {
            // Nothing to do: the response is what matters.
        }
    }
}
