package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcContextThreadLocalAccessorTest {

    private static final String ID = "0af7651916cd43dd8448eb211c80319c";

    private final MdcContextThreadLocalAccessor accessor = new MdcContextThreadLocalAccessor();

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void isRegisteredThroughTheServiceLoader() {
        assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                .anyMatch(candidate -> MdcContextThreadLocalAccessor.KEY.equals(candidate.key()));
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
            seen.put("context", MdcContext.traceId());
            seen.put("mdcTraceId", java.util.Optional.ofNullable(MDC.get("traceId")));
            seen.put("mdcTenant", java.util.Optional.ofNullable(MDC.get("tenant")));
        });
        String callerA = "11111111111111111111111111111111";
        try (AutoCloseable outer = MdcContext.open(callerA, Map.of("tenant", "dxb"))) {
            contextFree.run(); // e.g. a callback decorated earlier, invoked while serving caller A
            assertThat(MdcContext.traceId()).as("caller A restored").contains(callerA);
            assertThat(MDC.get("traceId")).isEqualTo(callerA);
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
        }
        assertThat(seen.get("context")).isEqualTo(java.util.Optional.empty());
        assertThat(seen.get("mdcTraceId")).isEqualTo(java.util.Optional.empty());
        assertThat(seen.get("mdcTenant")).isEqualTo(java.util.Optional.empty());
    }

    @Test
    void setValueOpensTheCapturedContextAndRestoreClosesIt() throws Exception {
        Map<String, String> captured;
        try (AutoCloseable scope = MdcContext.open(ID, Map.of("spanId", "b7ad6b7169203331"))) {
            captured = accessor.getValue();
        }
        assertThat(captured).containsEntry("traceId", ID).containsEntry("spanId", "b7ad6b7169203331");

        accessor.setValue(captured);
        assertThat(MdcContext.traceId()).contains(ID);
        assertThat(MDC.get("traceId")).isEqualTo(ID);
        assertThat(MDC.get("spanId")).isEqualTo("b7ad6b7169203331");

        accessor.restore(null);
        assertThat(MdcContext.traceId()).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void anEmptySnapshotHidesTheExecutingThreadsContextAndRestoreBringsItBack() throws Exception {
        String callerA = "11111111111111111111111111111111";
        try (AutoCloseable outer = MdcContext.open(callerA, Map.of("tenant", "dxb"))) {
            accessor.setValue();
            assertThat(MdcContext.traceId()).as("context-free task sees no caller").isEmpty();
            assertThat(MdcContext.asMap()).isEmpty();
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("tenant")).isNull();
            accessor.restore();
            assertThat(MdcContext.traceId()).contains(callerA);
            assertThat(MDC.get("traceId")).isEqualTo(callerA);
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aSnapshotReplacesTheExecutingThreadsContextWithoutInheritingItsExtras() throws Exception {
        String callerA = "11111111111111111111111111111111";
        try (AutoCloseable outer = MdcContext.open(callerA, Map.of("tenant", "dxb"))) {
            accessor.setValue(Map.of("traceId", ID, "spanId", "b7ad6b7169203331"));
            assertThat(MdcContext.traceId()).contains(ID);
            assertThat(MdcContext.asMap()).containsOnlyKeys("traceId", "spanId");
            assertThat(MDC.get("tenant")).as("caller A's extra must not leak into the task").isNull();
            assertThat(MDC.get("traceId")).isEqualTo(ID);
            accessor.restore(null);
            assertThat(MdcContext.asMap()).containsEntry("traceId", callerA).containsEntry("tenant", "dxb");
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
            assertThat(MDC.get("spanId")).isNull();
        }
    }

    @Test
    void anIdLessSnapshotRunsWithoutACorrelationIdButWithItsOwnExtras() throws Exception {
        try (AutoCloseable outer = MdcContext.open(ID, Map.of())) {
            accessor.setValue(Map.of("tenant", "auh"));
            assertThat(MdcContext.traceId()).isEmpty();
            assertThat(MdcContext.asMap()).containsExactly(Map.entry("tenant", "auh"));
            assertThat(MDC.get("traceId")).isNull();
            accessor.restore(null);
            assertThat(MdcContext.traceId()).contains(ID);
        }
    }

    @Test
    void propagatesThroughAContextSnapshotToAnotherThread() throws Exception {
        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().build();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Runnable wrapped;
            try (AutoCloseable scope = MdcContext.open(ID, Map.of())) {
                ContextSnapshot snapshot = factory.captureAll();
                wrapped = snapshot.wrap((Runnable) () -> {
                    assertThat(MdcContext.traceId()).contains(ID);
                });
            }
            pool.submit(wrapped).get();
            assertThat(pool.submit(() -> MdcContext.traceId().isEmpty() && MDC.get("traceId") == null).get())
                    .as("pool thread is clean after the wrapped task").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    // Codex round 1, finding 6: a trace put in the MDC directly (the outbox drainer's restore, Boot's tracing) is
    // captured, follows the work to another thread, and leaves both threads exactly as they were.
    @Test
    void aTraceWrittenDirectlyToTheMdcFollowsTheWorkAndIsCleanedUp() throws Exception {
        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().clearMissing(true).build();
        java.util.Map<String, String> seen = new java.util.concurrent.ConcurrentHashMap<>();
        Runnable task;
        MDC.put("traceId", ID);
        try {
            task = factory.captureAll().wrap(() -> java.util.Optional.ofNullable(MDC.get("traceId"))
                    .ifPresent(id -> seen.put("traceId", id)));
        } finally {
            MDC.remove("traceId");
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> MDC.put("traceId", "99999999999999999999999999999999")).get();
            worker.submit(task).get();
            assertThat(seen).containsEntry("traceId", ID);
            assertThat(worker.submit(() -> MDC.get("traceId")).get())
                    .as("the occupied worker's own trace is restored").isEqualTo("99999999999999999999999999999999");
        } finally {
            worker.shutdownNow();
        }
    }

    // An empty snapshot applied on a worker whose MDC holds another caller's trace hides it for the task only.
    @Test
    void anEmptySnapshotHidesADirectlyWrittenTraceForTheTaskOnly() {
        MDC.put("traceId", ID);
        MDC.put("spanId", "b7ad6b7169203331");
        accessor.setValue();
        try {
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("spanId")).isNull();
        } finally {
            accessor.restore();
        }
        assertThat(MDC.get("traceId")).isEqualTo(ID);
        assertThat(MDC.get("spanId")).isEqualTo("b7ad6b7169203331");
    }
}
