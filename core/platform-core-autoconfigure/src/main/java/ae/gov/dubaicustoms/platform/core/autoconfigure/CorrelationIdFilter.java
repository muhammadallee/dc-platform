package ae.gov.dubaicustoms.platform.core.autoconfigure;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that establishes the correlation context for every request from the current
 * W3C trace: it opens the {@link RequestContext} with the active span's trace ID (and publishes
 * {@code traceId}/{@code spanId} into the MDC) for the duration of the request, and always closes it.
 *
 * <p>The filter never parses, generates, or echoes correlation headers. Inbound {@code traceparent}
 * handling is the tracing bridge's job (Boot's {@code ServerHttpObservationFilter}, which runs
 * before this filter): a valid header continues the caller's trace, a missing or invalid one starts
 * a new trace. This filter only logs, at DEBUG, a sanitized and length-capped marker when a
 * {@code traceparent} was present but not valid, so the raw value never reaches a log line.
 *
 * <p>Registered by {@code CoreContextAutoConfiguration} after the observation filter and before
 * the Spring Security filter chain; define your own {@code CorrelationIdFilter} bean to replace it.
 *
 * <p>Thread-safe; the context it opens is confined to the request thread and always closed.
 * Constructor arguments must not be {@code null}.
 *
 * @since 0.1.0
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    /** W3C Trace Context, version 00: {@code 00-<32 hex trace-id>-<16 hex parent-id>-<2 hex flags>}. */
    private static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final String TRACEPARENT_HEADER = "traceparent";

    private static final int MAX_LOGGED_CHARS = 64;

    private final Tracer tracer;

    /**
     * Creates the filter.
     *
     * @param tracer the tracer whose current span carries the request's trace ID; never {@code null}
     */
    public CorrelationIdFilter(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        logRejectedTraceparent(request.getHeader(TRACEPARENT_HEADER));
        TraceContext trace = currentTraceContext();
        if (trace == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Map<String, String> extras = trace.spanId() == null ? Map.of() : Map.of("spanId", trace.spanId());
        try (AutoCloseable scope = RequestContext.open(new CorrelationId(trace.traceId()), extras)) {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // AutoCloseable.close() signature only; RequestContext scopes never throw.
            throw new ServletException(e);
        }
    }

    // A trace ID that is not a 128-bit W3C id (e.g. a 64-bit Brave id) is not a correlation id; the
    // request then runs without a RequestContext rather than with a fabricated one.
    private TraceContext currentTraceContext() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        if (context.traceId() == null || context.traceId().isEmpty()) {
            // No-op tracer (tracing disabled): no trace, so no correlation context.
            return null;
        }
        if (context.traceId().length() != 32) {
            log.debug("current span has no 128-bit trace id; request runs without a correlation context");
            return null;
        }
        return context;
    }

    private static void logRejectedTraceparent(String value) {
        if (value == null || TRACEPARENT.matcher(value).matches() || !log.isDebugEnabled()) {
            return;
        }
        log.debug("rejected inbound traceparent (length={}, sanitized='{}'); a new trace was started",
                value.length(), sanitize(value));
    }

    // Keeps only characters a traceparent may legally contain and caps the length, so neither
    // control characters (log injection) nor an oversized value can reach the log line.
    static String sanitize(String value) {
        StringBuilder kept = new StringBuilder(Math.min(value.length(), MAX_LOGGED_CHARS));
        for (int i = 0; i < value.length() && kept.length() < MAX_LOGGED_CHARS; i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == '-') {
                kept.append(c);
            } else {
                kept.append('?');
            }
        }
        if (value.length() > MAX_LOGGED_CHARS) {
            kept.append("...");
        }
        return kept.toString();
    }
}
