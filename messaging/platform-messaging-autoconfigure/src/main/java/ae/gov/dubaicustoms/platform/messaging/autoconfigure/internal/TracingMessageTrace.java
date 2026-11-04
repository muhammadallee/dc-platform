package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link MessageTrace} over Micrometer Tracing. Loaded only by the guarded configuration, and resolves its collaborators
 * per call, so it does not depend on the order auto-configurations register them. Thread-safe.
 */
public final class TracingMessageTrace implements MessageTrace {

    // Only these leave the propagator: baggage must not reach a message.
    private static final Set<String> TRACE_HEADERS = Set.of("traceparent", "tracestate");

    private final Supplier<Tracer> tracer;
    private final Supplier<Propagator> propagator;

    /**
     * Creates the trace support.
     *
     * @param tracer supplies the tracer, or {@code null} for none; never {@code null}
     * @param propagator supplies the propagator, or {@code null} for none; never {@code null}
     */
    public TracingMessageTrace(Supplier<Tracer> tracer, Supplier<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Map<String, String> currentHeaders() {
        Tracer currentTracer = tracer.get();
        Propagator currentPropagator = propagator.get();
        Span span = currentTracer != null ? currentTracer.currentSpan() : null;
        if (span == null || currentPropagator == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        currentPropagator.inject(span.context(), headers, (carrier, name, value) -> {
            if (TRACE_HEADERS.contains(name)) {
                carrier.put(name, value);
            }
        });
        return headers;
    }

    @Override
    public Scope continueFrom(Map<String, String> headers) {
        Tracer currentTracer = tracer.get();
        Propagator currentPropagator = propagator.get();
        if (currentTracer == null || currentPropagator == null) {
            return () -> { };
        }
        Span span = currentPropagator.extract(headers, Map::get).name("platform.messaging.handle")
                .kind(Span.Kind.CONSUMER).start();
        Tracer.SpanInScope inScope = currentTracer.withSpan(span);
        return () -> {
            inScope.close();
            span.end();
        };
    }

    @Override
    public Runnable carry(Runnable action) {
        Tracer currentTracer = tracer.get();
        TraceContext context = currentTracer != null ? currentTracer.currentTraceContext().context() : null;
        if (context == null) {
            return action;
        }
        return () -> {
            try (var ignored = currentTracer.currentTraceContext().newScope(context)) {
                action.run();
            }
        };
    }
}
