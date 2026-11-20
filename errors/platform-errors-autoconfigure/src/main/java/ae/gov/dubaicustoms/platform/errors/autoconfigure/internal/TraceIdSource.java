package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import jakarta.servlet.http.HttpServletRequest;

// Where the error body's traceId comes from. The auto-configuration backs it with Micrometer's Tracer (falling back
// to the request's observation on an ERROR dispatch); without tracing on the classpath there is none. A plain
// interface so the advice never loads a tracing type when tracing is absent.
@FunctionalInterface
public interface TraceIdSource {

    /** No tracing: the body carries no traceId. */
    TraceIdSource NONE = request -> null;

    /**
     * Returns the trace ID of the request being served.
     *
     * @param request the request; may be {@code null} outside a servlet request
     * @return the 32-hex trace ID, or {@code null} when there is none
     */
    String traceId(HttpServletRequest request);
}
