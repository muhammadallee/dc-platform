package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Writes an RFC-9457 problem body for anonymous requests to protected resources (401). */
public final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ProblemResponses.write(response, 401, "Unauthorized",
                "Full authentication is required to access this resource");
    }
}
