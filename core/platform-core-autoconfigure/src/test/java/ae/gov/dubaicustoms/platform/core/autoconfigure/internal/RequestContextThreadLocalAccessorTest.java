package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RequestContextThreadLocalAccessorTest {

    private static final CorrelationId ID = new CorrelationId("0af7651916cd43dd8448eb211c80319c");

    private final RequestContextThreadLocalAccessor accessor = new RequestContextThreadLocalAccessor();

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void isRegisteredThroughTheServiceLoader() {
        assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                .anyMatch(candidate -> RequestContextThreadLocalAccessor.KEY.equals(candidate.key()));
    }

    @Test
    void capturesAbsenceExplicitly() {
        // Never null: a null value would drop the accessor from the snapshot entirely.
        assertThat(accessor.getValue()).isNotNull().isEmpty();
    }

    @Test
    void bootsDefaultDecoratorRunsAContextFreeTaskWithoutTheExecutingCallersContext() throws Exception {
        // Boot wires ContextPropagatingTaskDecorator with its no-arg constructor (clearMissing=false):
        // the path the explicit-absence capture exists for.
        org.springframework.core.task.support.ContextPropagatingTaskDecorator decorator =
                new org.springframework.core.task.support.ContextPropagatingTaskDecorator();
        java.util.Map<String, Object> seen = new java.util.HashMap<>();
        Runnable contextFree = decorator.decorate(() -> {
            seen.put("context", RequestContext.correlationId());
            seen.put("mdcTraceId", java.util.Optional.ofNullable(MDC.get("traceId")));
            seen.put("mdcTenant", java.util.Optional.ofNullable(MDC.get("tenant")));
        });
        CorrelationId callerA = new CorrelationId("11111111111111111111111111111111");
        try (AutoCloseable outer = RequestContext.open(callerA, Map.of("tenant", "dxb"))) {
            contextFree.run(); // e.g. a callback decorated earlier, invoked while serving caller A
            assertThat(RequestContext.correlationId()).as("caller A restored").contains(callerA);
            assertThat(MDC.get("traceId")).isEqualTo(callerA.value());
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
        }
        assertThat(seen.get("context")).isEqualTo(java.util.Optional.empty());
        assertThat(seen.get("mdcTraceId")).isEqualTo(java.util.Optional.empty());
        assertThat(seen.get("mdcTenant")).isEqualTo(java.util.Optional.empty());
    }

    @Test
    void setValueOpensTheCapturedContextAndRestoreClosesIt() throws Exception {
        Map<String, String> captured;
        try (AutoCloseable scope = RequestContext.open(ID, Map.of("spanId", "b7ad6b7169203331"))) {
            captured = accessor.getValue();
        }
        assertThat(captured).containsEntry("traceId", ID.value()).containsEntry("spanId", "b7ad6b7169203331");

        accessor.setValue(captured);
        assertThat(RequestContext.correlationId()).contains(ID);
        assertThat(MDC.get("traceId")).isEqualTo(ID.value());
        assertThat(MDC.get("spanId")).isEqualTo("b7ad6b7169203331");

        accessor.restore(null);
        assertThat(RequestContext.correlationId()).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void anEmptySnapshotHidesTheExecutingThreadsContextAndRestoreBringsItBack() throws Exception {
        CorrelationId callerA = new CorrelationId("11111111111111111111111111111111");
        try (AutoCloseable outer = RequestContext.open(callerA, Map.of("tenant", "dxb"))) {
            accessor.setValue();
            assertThat(RequestContext.correlationId()).as("context-free task sees no caller").isEmpty();
            assertThat(RequestContext.asMap()).isEmpty();
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("tenant")).isNull();
            accessor.restore();
            assertThat(RequestContext.correlationId()).contains(callerA);
            assertThat(MDC.get("traceId")).isEqualTo(callerA.value());
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aSnapshotReplacesTheExecutingThreadsContextWithoutInheritingItsExtras() throws Exception {
        CorrelationId callerA = new CorrelationId("11111111111111111111111111111111");
        try (AutoCloseable outer = RequestContext.open(callerA, Map.of("tenant", "dxb"))) {
            accessor.setValue(Map.of("traceId", ID.value(), "spanId", "b7ad6b7169203331"));
            assertThat(RequestContext.correlationId()).contains(ID);
            assertThat(RequestContext.asMap()).containsOnlyKeys("traceId", "spanId");
            assertThat(MDC.get("tenant")).as("caller A's extra must not leak into the task").isNull();
            assertThat(MDC.get("traceId")).isEqualTo(ID.value());
            accessor.restore(null);
            assertThat(RequestContext.asMap()).containsEntry("traceId", callerA.value()).containsEntry("tenant", "dxb");
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
            assertThat(MDC.get("spanId")).isNull();
        }
    }

    @Test
    void anIdLessSnapshotRunsWithoutACorrelationIdButWithItsOwnExtras() throws Exception {
        try (AutoCloseable outer = RequestContext.open(ID, Map.of())) {
            accessor.setValue(Map.of("tenant", "auh"));
            assertThat(RequestContext.correlationId()).isEmpty();
            assertThat(RequestContext.asMap()).containsExactly(Map.entry("tenant", "auh"));
            assertThat(MDC.get("traceId")).isNull();
            accessor.restore(null);
            assertThat(RequestContext.correlationId()).contains(ID);
        }
    }

    @Test
    void propagatesThroughAContextSnapshotToAnotherThread() throws Exception {
        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().build();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Runnable wrapped;
            try (AutoCloseable scope = RequestContext.open(ID, Map.of())) {
                ContextSnapshot snapshot = factory.captureAll();
                wrapped = snapshot.wrap((Runnable) () -> {
                    assertThat(RequestContext.correlationId()).contains(ID);
                });
            }
            pool.submit(wrapped).get();
            assertThat(pool.submit(() -> RequestContext.correlationId().isEmpty() && MDC.get("traceId") == null).get())
                    .as("pool thread is clean after the wrapped task").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
