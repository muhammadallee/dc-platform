package ae.gov.dubaicustoms.platform.core.autoconfigure;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that establishes the correlation context for every request: reads the
 * correlation header (generating a fresh id when absent or malformed, if configured), opens the
 * {@link RequestContext} for the duration of the request, and echoes the header on the response.
 *
 * <p>Registered by {@code CoreContextAutoConfiguration} at highest filter precedence; define your
 * own {@code CorrelationIdFilter} bean to replace it.
 *
 * <p>Thread-safe; the context it opens is confined to the request thread and always closed.
 * Constructor arguments must not be {@code null}.
 *
 * @since 0.1.0
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final String headerName;
    private final boolean generateIfMissing;

    /**
     * Creates the filter.
     *
     * @param headerName the HTTP header carrying the correlation id; never {@code null}
     * @param generateIfMissing whether to mint a fresh id when the request carries none
     */
    public CorrelationIdFilter(String headerName, boolean generateIfMissing) {
        this.headerName = headerName;
        this.generateIfMissing = generateIfMissing;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CorrelationId id = resolve(request.getHeader(headerName));
        if (id == null) {
            filterChain.doFilter(request, response);
            return;
        }
        // Echo before the chain so the header survives an early response commit.
        response.setHeader(headerName, id.value());
        try (AutoCloseable scope = RequestContext.open(id, Map.of())) {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // AutoCloseable.close() signature only; RequestContext scopes never throw.
            throw new ServletException(e);
        }
    }

    private CorrelationId resolve(String headerValue) {
        if (headerValue != null) {
            try {
                return new CorrelationId(headerValue);
            } catch (IllegalArgumentException e) {
                log.debug("ignoring malformed {} header: {}", headerName, e.getMessage());
            }
        }
        return generateIfMissing ? CorrelationId.random() : null;
    }
}
