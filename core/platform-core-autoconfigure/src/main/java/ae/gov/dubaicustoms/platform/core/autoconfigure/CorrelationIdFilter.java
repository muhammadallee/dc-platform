package ae.gov.dubaicustoms.platform.core.autoconfigure;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Servlet filter that establishes the correlation context for every request from the current
 * W3C trace: it opens the {@link RequestContext} with the active span's trace ID (and publishes
 * {@code traceId}/{@code spanId} into the MDC) for the duration of each dispatch, and always closes it.
 *
 * <p>The filter never parses, generates, or echoes correlation headers. Inbound {@code traceparent}
 * handling is the tracing bridge's job (Boot's {@code ServerHttpObservationFilter}, which runs
 * before this filter): a valid header continues the caller's trace, a missing or invalid one starts
 * a new trace. Whether a present header was rejected is judged by that outcome alone — the current
 * trace ID differs from the one the header names — so this filter never second-guesses the
 * propagator's rules. A rejection is logged at DEBUG as a fixed marker with the header's length only;
 * no header content ever reaches a log line.
 *
 * <p>Runs on REQUEST, ASYNC and ERROR dispatches: an asynchronous handler that completes or fails on a
 * redispatch, and a {@code sendError} rendered by the servlet error page, still see the request's
 * trace. On an ERROR dispatch the observation filter has no open scope, so the trace is taken from the
 * observation it stored on the request.
 *
 * <p>Registered by {@code CoreContextAutoConfiguration} after the observation filter and before
 * the Spring Security filter chain; define your own {@code CorrelationIdFilter} bean to replace it.
 *
 * <p>Thread-safe; the context it opens is confined to the dispatching thread and always closed.
 * Constructor arguments must not be {@code null}.
 *
 * @since 0.1.0
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private static final String TRACEPARENT_HEADER = "traceparent";

    /** Offset and length of the trace-id field in a version-00 {@code traceparent}. */
    private static final int TRACE_ID_START = 3;
    private static final int TRACE_ID_END = 35;

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
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        TraceContext trace = currentTraceContext(request);
        if (!isAsyncDispatch(request) && request.getDispatcherType() == jakarta.servlet.DispatcherType.REQUEST) {
            logIfRejected(request.getHeader(TRACEPARENT_HEADER), trace);
        }
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
    private TraceContext currentTraceContext(HttpServletRequest request) {
        Span span = tracer.currentSpan();
        if (span == null || isEmpty(span.context())) {
            span = observedSpan(request);
        }
        if (span == null || isEmpty(span.context())) {
            return null;
        }
        TraceContext context = span.context();
        if (context.traceId().length() != 32) {
            log.debug("current span has no 128-bit trace id; request runs without a correlation context");
            return null;
        }
        return context;
    }

    // ERROR dispatches run outside the observation filter's scope; its observation (and so the span)
    // is still attached to the request.
    private static Span observedSpan(HttpServletRequest request) {
        return ServerHttpObservationFilter.findObservationContext(request)
                .<TracingObservationHandler.TracingContext>map(context -> context.get(TracingObservationHandler.TracingContext.class))
                .map(TracingObservationHandler.TracingContext::getSpan)
                .orElse(null);
    }

    private static boolean isEmpty(TraceContext context) {
        // A no-op tracer (tracing disabled) yields spans with an empty trace id.
        return context == null || context.traceId() == null || context.traceId().isEmpty();
    }

    // A present traceparent was rejected when the propagator did not continue the trace it names. The
    // marker deliberately carries no header content, only its length.
    private static void logIfRejected(String header, TraceContext trace) {
        // Without a trace (tracing disabled) there is no propagator outcome to judge.
        if (header == null || trace == null || !log.isDebugEnabled()) {
            return;
        }
        if (!continues(header, trace)) {
            log.debug("rejected inbound traceparent (length={}); a new trace was started", header.length());
        }
    }

    static boolean continues(String header, TraceContext trace) {
        return trace != null
                && header.length() >= TRACE_ID_END
                && header.regionMatches(TRACE_ID_START, trace.traceId(), 0, TRACE_ID_END - TRACE_ID_START);
    }
}
