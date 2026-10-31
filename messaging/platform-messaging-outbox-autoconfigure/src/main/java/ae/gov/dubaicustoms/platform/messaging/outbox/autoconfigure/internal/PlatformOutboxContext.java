package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the caller's trace and principal for the outbox row, and restores the stored trace on the drainer's thread so
 * its log lines land on the original trace. Only {@code traceparent}/{@code tracestate} leave here (plan §277).
 * Thread-safe.
 */
public final class PlatformOutboxContext implements OutboxContext {

    private static final Logger log = LoggerFactory.getLogger(PlatformOutboxContext.class);
    private static final String TRACEPARENT = "traceparent";

    private final TraceSource trace;
    private final PrincipalSource principal;

    public PlatformOutboxContext(TraceSource trace, PrincipalSource principal) {
        this.trace = trace;
        this.principal = principal;
    }

    /** Supplies the current trace as W3C headers; empty without one. */
    @FunctionalInterface
    public interface TraceSource {
        Map<String, String> headers();
    }

    /** Supplies the current principal's subject id, or {@code null}. */
    @FunctionalInterface
    public interface PrincipalSource {
        String subject();
    }

    /**
     * The trace source over Micrometer Tracing.
     *
     * @param tracer the tracer; never {@code null}
     * @param propagator writes the W3C headers; never {@code null}
     * @return the source; never {@code null}
     */
    public static TraceSource traceSource(Tracer tracer, Propagator propagator) {
        return () -> {
            Span span = tracer.currentSpan();
            TraceContext context = span != null ? span.context() : null;
            if (context == null) {
                return Map.of();
            }
            Map<String, String> headers = new LinkedHashMap<>();
            propagator.inject(context, headers, Map::put);
            return headers;
        };
    }

    @Override
    public Map<String, String> traceHeaders() {
        return trace.headers();
    }

    @Override
    public String principal() {
        return principal.subject();
    }

    @Override
    public Scope restore(Map<String, String> headers) {
        String traceId = traceId(headers.get(TRACEPARENT));
        if (traceId == null) {
            return () -> { };
        }
        AutoCloseable scope = RequestContext.open(new CorrelationId(traceId), Map.of());
        return () -> {
            try {
                scope.close();
            } catch (Exception e) {
                log.debug("closing the restored outbox context failed: {}", e.getClass().getName());
            }
        };
    }

    // version-traceId-spanId-flags; anything else is not restored, and the send goes on.
    private static String traceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return parts.length == 4 && parts[1].matches("[0-9a-f]{32}") ? parts[1] : null;
    }
}
