package ae.gov.dubaicustoms.platform.core.autoconfigure;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Finds the W3C trace of the request being served, for the correlation filter and the error body. Platform
 * infrastructure: a service reads the trace from {@link Tracer} itself.
 *
 * <p>The current span wins. On an ERROR dispatch no span is in scope, so the span of the observation that Boot's
 * {@code ServerHttpObservationFilter} stored on the request is used. A trace ID that is not 128-bit (for example a
 * 64-bit Brave ID) or a no-op tracer's empty ID counts as no trace: the platform never invents or truncates one.
 *
 * <p>Stateless and thread-safe.
 */
public final class RequestTrace {

    private static final Logger log = LoggerFactory.getLogger(RequestTrace.class);

    private static final int W3C_TRACE_ID_LENGTH = 32;

    private RequestTrace() {
    }

    /**
     * Returns the trace of the request being served.
     *
     * @param tracer the tracer; never {@code null} ({@link Tracer#NOOP} when tracing is off)
     * @param request the request, or {@code null} outside a servlet request
     * @return the 128-bit trace context, or {@code null} when there is none
     */
    public static TraceContext current(Tracer tracer, HttpServletRequest request) {
        Span span = tracer.currentSpan();
        if ((span == null || isEmpty(span.context())) && request != null) {
            span = observedSpan(request);
        }
        if (span == null || isEmpty(span.context())) {
            return null;
        }
        TraceContext context = span.context();
        if (context.traceId().length() != W3C_TRACE_ID_LENGTH) {
            log.debug("current span has no 128-bit trace id; no correlation context");
            return null;
        }
        return context;
    }

    // ERROR dispatches run outside the observation filter's scope; its observation (and so the span) is still
    // attached to the request.
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
}
