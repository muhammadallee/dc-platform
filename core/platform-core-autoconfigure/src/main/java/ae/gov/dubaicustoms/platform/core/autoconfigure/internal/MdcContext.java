package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * The platform's correlation context on the current thread: the trace ID (MDC key {@code traceId}, Boot tracing's key)
 * plus extras such as {@code spanId}, published into the SLF4J {@link MDC} by scopes that restore the previous MDC
 * exactly when closed.
 *
 * <p>Opened by the correlation filter for each dispatch and re-applied on executor threads by
 * {@link MdcContextThreadLocalAccessor}. Services never use it: they read the trace from Micrometer's {@code Tracer},
 * or the {@code traceId} MDC key in a log line.
 *
 * <p>Scopes nest: a read sees the innermost scope, and an isolating scope ({@link #replace}) hides every outer one.
 * The context is confined to the opening thread. Methods never return {@code null}.
 */
public final class MdcContext {

    /** MDC key of the trace ID; the key Spring Boot's tracing uses. */
    public static final String TRACE_ID_KEY = "traceId";

    /** MDC key of the span ID; the key Spring Boot's tracing uses. */
    public static final String SPAN_ID_KEY = "spanId";

    // Stack of open scopes per thread: innermost-wins reads and exact restoration on close.
    private static final ThreadLocal<Deque<Scope>> SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    private MdcContext() {
    }

    /**
     * Returns the trace ID of the innermost open scope.
     *
     * @return the trace ID, or empty when no scope is open or the innermost isolating scope has none
     */
    public static Optional<String> traceId() {
        Scope scope = SCOPES.get().peek();
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.traceId);
    }

    /**
     * Returns an immutable snapshot of the visible context: the trace ID under {@value #TRACE_ID_KEY} plus every extra,
     * the innermost scope winning on a shared key.
     *
     * @return the snapshot; an empty map when no context is visible
     */
    public static Map<String, String> asMap() {
        Deque<Scope> scopes = SCOPES.get();
        if (scopes.isEmpty()) {
            return Map.of();
        }
        // Only scopes up to (and including) the innermost isolating one are visible.
        Deque<Scope> visible = new ArrayDeque<>();
        for (Scope scope : scopes) {
            visible.push(scope);
            if (scope.isolating) {
                break;
            }
        }
        Map<String, String> merged = new LinkedHashMap<>();
        // visible is now outermost-first, so inner puts overwrite outer ones.
        for (Scope scope : visible) {
            if (scope.traceId != null) {
                merged.put(TRACE_ID_KEY, scope.traceId);
            }
            merged.putAll(scope.extras);
        }
        return Map.copyOf(merged);
    }

    /**
     * Opens a scope that publishes the trace ID and extras into the MDC. Always close it (try-with-resources).
     *
     * @param traceId the trace ID; never {@code null}
     * @param extras further entries, for example {@code spanId}; never {@code null}, may be empty
     * @return the handle that restores the previous context and MDC
     * @throws NullPointerException if an argument is null
     */
    public static AutoCloseable open(String traceId, Map<String, String> extras) {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(extras, "extras must not be null");
        Scope scope = new Scope(traceId, Map.copyOf(extras), false);
        SCOPES.get().push(scope);
        scope.publish(Map.of());
        return scope;
    }

    /**
     * Opens an isolating scope: while it is open the thread sees exactly {@code traceId} and {@code extras}. Every outer
     * scope's entries are removed from the MDC, and so are {@code traceId} and {@code spanId} written there by anyone
     * else (Boot's tracing, the outbox drainer). With a {@code null} trace ID the thread runs with no correlation
     * context. Used to apply a propagated snapshot on a thread that may already serve another caller. Always close it;
     * closing restores every removed entry exactly.
     *
     * @param traceId the trace ID, or {@code null} for "no context"
     * @param extras the complete set of extras; never {@code null}, may be empty
     * @return the handle that restores the previous context and MDC
     * @throws NullPointerException if {@code extras} is null
     */
    public static AutoCloseable replace(String traceId, Map<String, String> extras) {
        Objects.requireNonNull(extras, "extras must not be null");
        Map<String, String> hidden = new HashMap<>(asMap());
        for (String key : new String[] {TRACE_ID_KEY, SPAN_ID_KEY}) {
            String present = MDC.get(key);
            if (present != null) {
                hidden.put(key, present);
            }
        }
        Scope scope = new Scope(traceId, Map.copyOf(extras), true);
        SCOPES.get().push(scope);
        scope.publish(hidden);
        return scope;
    }

    /** One open() or replace(): its entries plus the MDC values it shadowed, for an exact restore. */
    private static final class Scope implements AutoCloseable {

        private final String traceId;
        private final Map<String, String> extras;
        private final boolean isolating;
        private final Map<String, String> shadowed = new HashMap<>();
        private boolean closed;

        private Scope(String traceId, Map<String, String> extras, boolean isolating) {
            this.traceId = traceId;
            this.extras = extras;
            this.isolating = isolating;
        }

        // hidden: the entries outer scopes published, which an isolating scope removes from the MDC.
        private void publish(Map<String, String> hidden) {
            hidden.keySet().forEach(this::remove);
            if (traceId != null) {
                put(TRACE_ID_KEY, traceId);
            }
            extras.forEach(this::put);
        }

        private void put(String key, String value) {
            shadowed.putIfAbsent(key, MDC.get(key));
            MDC.put(key, value);
        }

        private void remove(String key) {
            shadowed.putIfAbsent(key, MDC.get(key));
            MDC.remove(key);
        }

        @Override
        public void close() {
            if (closed) {
                return; // already unwound by an outer scope's close, or closed twice
            }
            Deque<Scope> scopes = SCOPES.get();
            if (!scopes.contains(this)) {
                closed = true;
                if (scopes.isEmpty()) {
                    SCOPES.remove();
                }
                return;
            }
            // Out-of-order close: inner scopes still open are unwound first, newest first, each restoring what it
            // shadowed, so the MDC ends exactly as it was before this scope opened.
            while (!scopes.isEmpty()) {
                Scope top = scopes.pop();
                top.restore();
                if (top == this) {
                    break;
                }
            }
            if (scopes.isEmpty()) {
                SCOPES.remove();
            }
        }

        private void restore() {
            closed = true;
            shadowed.forEach((key, previous) -> {
                if (previous == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, previous);
                }
            });
        }
    }
}
