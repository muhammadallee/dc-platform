package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The current trace as W3C headers, over Micrometer Tracing. A class of its own, loaded only by the guarded tracing
 * configuration: a tracing type in a signature of an always-present bean breaks reflection without that optional jar.
 * Resolves the tracer on each call, so it does not depend on the order auto-configurations register them. Thread-safe.
 */
public final class TracingOutboxTraceSource implements PlatformOutboxContext.TraceSource {

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public TracingOutboxTraceSource(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Map<String, String> headers() {
        Tracer currentTracer = tracer.getIfUnique();
        Propagator currentPropagator = propagator.getIfUnique();
        Span span = currentTracer != null ? currentTracer.currentSpan() : null;
        if (span == null || currentPropagator == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        currentPropagator.inject(span.context(), headers, Map::put);
        return headers;
    }
}
