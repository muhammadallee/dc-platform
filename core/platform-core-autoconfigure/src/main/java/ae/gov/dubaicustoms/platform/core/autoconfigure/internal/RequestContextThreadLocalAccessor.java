package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Micrometer context-propagation accessor for {@link RequestContext}: captures the current context
 * on the submitting thread and re-opens it on the executing thread, so {@code @Async} methods and
 * Boot-built executors (wrapped by {@code ContextPropagatingTaskDecorator}) see the caller's trace ID
 * in {@code RequestContext} and the MDC. Registered through {@code META-INF/services}.
 *
 * <p>Applying a snapshot REPLACES whatever context the executing thread already has (an isolating
 * {@link RequestContext#replace} scope): a task captured without a context runs without one, and a
 * task never inherits the executing thread's extras — even when it runs on a thread that is itself
 * serving another caller (caller-runs policies, nested propagation). Every scope is closed by the
 * matching {@code restore}, which brings back the thread's previous context and MDC exactly.
 */
public final class RequestContextThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    /** Context-propagation key; stable, other libraries never use it. */
    public static final String KEY = "dc.platform.request-context";

    private static final String TRACE_ID_KEY = "traceId";

    private static final ThreadLocal<Deque<AutoCloseable>> OPENED = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public Map<String, String> getValue() {
        Map<String, String> snapshot = RequestContext.asMap();
        return snapshot.isEmpty() ? null : snapshot;
    }

    @Override
    public void setValue(Map<String, String> value) {
        CorrelationId id = Optional.ofNullable(value.get(TRACE_ID_KEY)).map(CorrelationId::new).orElse(null);
        Map<String, String> extras = new LinkedHashMap<>(value);
        extras.remove(TRACE_ID_KEY);
        OPENED.get().push(RequestContext.replace(id, extras));
    }

    @Override
    public void setValue() {
        // The snapshot had no context: the task must run with none, whatever this thread carries.
        OPENED.get().push(RequestContext.replace(null, Map.of()));
    }

    @Override
    public void restore(Map<String, String> previousValue) {
        closeLatest();
    }

    @Override
    public void restore() {
        closeLatest();
    }

    private static void closeLatest() {
        Deque<AutoCloseable> opened = OPENED.get();
        AutoCloseable latest = opened.poll();
        if (opened.isEmpty()) {
            OPENED.remove();
        }
        if (latest == null) {
            return;
        }
        try {
            latest.close();
        } catch (Exception e) {
            // RequestContext scopes never throw; AutoCloseable's signature only.
            throw new IllegalStateException(e);
        }
    }
}
