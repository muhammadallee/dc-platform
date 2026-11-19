package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import io.micrometer.context.ThreadLocalAccessor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Micrometer context-propagation accessor for {@link MdcContext}: captures the current context on the
 * submitting thread and re-opens it on the executing thread, so {@code @Async} methods and Boot-built
 * executors (wrapped by {@code ContextPropagatingTaskDecorator}) see the caller's trace ID in the MDC.
 * Registered through {@code META-INF/services}.
 *
 * <p>Applying a snapshot REPLACES whatever context the executing thread already has (an isolating
 * {@link MdcContext#replace} scope): a task captured without a context runs without one, and a
 * task never inherits the executing thread's extras — even when it runs on a thread that is itself
 * serving another caller (caller-runs policies, nested propagation). Every scope is closed by the
 * matching {@code restore}, which brings back the thread's previous context and MDC exactly.
 */
public final class MdcContextThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    /** Context-propagation key; stable, other libraries never use it. */
    public static final String KEY = "dc.platform.request-context";

    private static final ThreadLocal<Deque<AutoCloseable>> OPENED = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public Object key() {
        return KEY;
    }

    // Never null: an empty map records "no context" in the snapshot, so applying it isolates the task
    // even under a decorator whose snapshot factory does not clear missing values (Boot's default).
    // The trace keys are read from the MDC itself, not only from platform scopes: a trace restored by the
    // outbox drainer, or put there by Boot's tracing, follows the work too.
    @Override
    public Map<String, String> getValue() {
        Map<String, String> value = new LinkedHashMap<>(MdcContext.asMap());
        for (String key : new String[] {MdcContext.TRACE_ID_KEY, MdcContext.SPAN_ID_KEY}) {
            String present = MDC.get(key);
            if (present == null) {
                value.remove(key);
            } else {
                value.put(key, present);
            }
        }
        return Map.copyOf(value);
    }

    @Override
    public void setValue(Map<String, String> value) {
        Map<String, String> extras = new LinkedHashMap<>(value);
        String traceId = extras.remove(MdcContext.TRACE_ID_KEY);
        OPENED.get().push(MdcContext.replace(traceId, extras));
    }

    @Override
    public void setValue() {
        // The snapshot had no context: the task must run with none, whatever this thread carries.
        OPENED.get().push(MdcContext.replace(null, Map.of()));
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
            // MdcContext scopes never throw; AutoCloseable's signature only.
            throw new IllegalStateException(e);
        }
    }
}
