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
    void capturesNothingWithoutAContext() {
        assertThat(accessor.getValue()).isNull();
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
    void emptyOrIdLessSnapshotsOpenNothingAndStillUnwindOneLevel() throws Exception {
        try (AutoCloseable outer = RequestContext.open(ID, Map.of())) {
            accessor.setValue();
            accessor.setValue(Map.of("tenant", "dxb"));
            assertThat(RequestContext.correlationId()).contains(ID);
            accessor.restore();
            accessor.restore(Map.of());
            // The outer scope was opened by someone else and must survive both restores.
            assertThat(RequestContext.correlationId()).contains(ID);
        }
        accessor.restore();
        assertThat(RequestContext.correlationId()).isEmpty();
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
