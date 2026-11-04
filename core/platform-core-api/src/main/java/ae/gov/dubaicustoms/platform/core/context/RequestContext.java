package ae.gov.dubaicustoms.platform.core.context;

import ae.gov.dubaicustoms.platform.core.PlatformInternal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apiguardian.api.API;
import org.slf4j.MDC;

/**
 * Read-only static access to the current request context (correlation id plus platform-managed
 * extras). The correlation id is the W3C trace ID of the current span (see {@link CorrelationId}).
 * Backed by SLF4J's {@link MDC}: the id is published under {@code traceId} (the key Spring Boot's
 * tracing uses), so {@code %X{traceId}} and JSON logs carry it; platform auto-configuration
 * propagates the context to {@code @Async} and Boot-built executors via Micrometer
 * context-propagation.
 *
 * <p>Static access is READ-ONLY convenience for application code; population is done exclusively
 * by platform filters and interceptors via {@link #open(CorrelationId, Map)}.
 *
 * <pre>{@code
 * RequestContext.correlationId()
 *         .ifPresent(id -> auditTrail.record(id, event));
 * }</pre>
 *
 * <p>Thread-safe: the context is confined to the populating thread and never leaks across
 * threads. Methods never return {@code null}.
 *
 * @since 0.1.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class RequestContext {

    /** MDC key under which the correlation id (the trace ID) is published; Boot tracing's key. */
    private static final String CORRELATION_ID_KEY = "traceId";

    // Stack of open scopes per thread; supports nested open() (e.g. a message handler inside a
    // web request) with innermost-wins reads and exact restoration on close.
    private static final ThreadLocal<Deque<Scope>> SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    private RequestContext() {
    }

    /**
     * Returns the correlation id of the innermost open context, if any.
     *
     * @return the current correlation id, or empty when no context is open on this thread
     */
    public static Optional<CorrelationId> correlationId() {
        Scope scope = SCOPES.get().peek();
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.id);
    }

    /**
     * Returns an immutable snapshot of the current context for logging or messaging headers:
     * the correlation id plus all extras, innermost scope winning on key collisions.
     *
     * @return the snapshot; empty map when no context is open on this thread
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
            if (scope.id != null) {
                merged.put(CORRELATION_ID_KEY, scope.id.value());
            }
            merged.putAll(scope.extras);
        }
        return Map.copyOf(merged);
    }

    /**
     * Opens a context scope on the current thread: publishes the correlation id and extras into
     * the {@link MDC} and returns a handle that restores the previous MDC state exactly.
     * Platform infrastructure only; always close (use try-with-resources).
     *
     * @param id the correlation id to publish; never {@code null}
     * @param extras additional context entries (e.g. tenant); never {@code null}, may be empty
     * @return handle restoring the previous state; close at the end of the request
     * @throws NullPointerException if {@code id} or {@code extras} is null
     */
    @PlatformInternal
    public static AutoCloseable open(CorrelationId id, Map<String, String> extras) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(extras, "extras must not be null");
        Scope scope = new Scope(id, Map.copyOf(extras), false);
        SCOPES.get().push(scope);
        scope.publish(Map.of());
        return scope;
    }

    /**
     * Opens an isolating scope: for its lifetime the current thread sees exactly {@code id} and
     * {@code extras} — every outer scope (its id, its extras and their MDC entries) is hidden — and
     * closing it restores the previous context and MDC exactly. With a {@code null} id the thread runs
     * with no correlation context at all. Used to apply a propagated context snapshot on a thread that
     * may already carry another caller's context. Platform infrastructure only; always close.
     *
     * @param id the correlation id to publish, or {@code null} for "no context"
     * @param extras the complete set of extras for the scope; never {@code null}, may be empty
     * @return handle restoring the previous state
     * @throws NullPointerException if {@code extras} is null
     * @since 1.0.0
     */
    @PlatformInternal
    public static AutoCloseable replace(CorrelationId id, Map<String, String> extras) {
        Objects.requireNonNull(extras, "extras must not be null");
        Map<String, String> hidden = asMap();
        Scope scope = new Scope(id, Map.copyOf(extras), true);
        SCOPES.get().push(scope);
        scope.publish(hidden);
        return scope;
    }

    /** One open()/replace() invocation: its entries plus the MDC values it shadowed, for exact restore. */
    private static final class Scope implements AutoCloseable {

        private final CorrelationId id;
        private final Map<String, String> extras;
        private final boolean isolating;
        private final Map<String, String> shadowed = new HashMap<>();

        private Scope(CorrelationId id, Map<String, String> extras, boolean isolating) {
            this.id = id;
            this.extras = extras;
            this.isolating = isolating;
        }

        // hidden: the entries outer scopes published, which an isolating scope removes from the MDC.
        private void publish(Map<String, String> hidden) {
            hidden.keySet().forEach(this::remove);
            if (id != null) {
                put(CORRELATION_ID_KEY, id.value());
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
            Deque<Scope> scopes = SCOPES.get();
            // Tolerate out-of-order closes defensively: pop until this scope is gone.
            while (!scopes.isEmpty() && scopes.pop() != this) {
                // popped stale inner scopes; their MDC state is restored by our shadowed map below
            }
            shadowed.forEach((key, previous) -> {
                if (previous == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, previous);
                }
            });
            if (scopes.isEmpty()) {
                SCOPES.remove();
            }
        }
    }
}
