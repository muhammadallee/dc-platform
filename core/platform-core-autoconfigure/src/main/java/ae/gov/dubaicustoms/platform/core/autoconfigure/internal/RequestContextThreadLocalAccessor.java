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
 * <p>Every scope this accessor opens is closed by the matching {@code restore}, so pool threads
 * return to an empty context and an empty MDC after each task.
 */
public final class RequestContextThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    /** Context-propagation key; stable, other libraries never use it. */
    public static final String KEY = "dc.platform.request-context";

    private static final String TRACE_ID_KEY = "traceId";

    // One entry per setValue: the scope it opened, or NONE when it opened nothing, so restore()
    // always pops exactly what the matching setValue pushed.
    private static final AutoCloseable NONE = () -> {
    };

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
        Optional<CorrelationId> id = Optional.ofNullable(value.get(TRACE_ID_KEY)).map(CorrelationId::new);
        if (id.isEmpty()) {
            OPENED.get().push(NONE);
            return;
        }
        Map<String, String> extras = new LinkedHashMap<>(value);
        extras.remove(TRACE_ID_KEY);
        OPENED.get().push(RequestContext.open(id.get(), extras));
    }

    @Override
    public void setValue() {
        // The snapshot had no context: nothing to open, but restore() still pops one entry.
        OPENED.get().push(NONE);
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
