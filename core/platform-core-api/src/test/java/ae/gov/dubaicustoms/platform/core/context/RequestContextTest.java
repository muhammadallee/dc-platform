package ae.gov.dubaicustoms.platform.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RequestContextTest {

    private static final CorrelationId ID = new CorrelationId("0123456789abcdef0123456789abcdef");

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void emptyWhenNoContextIsOpen() {
        assertThat(RequestContext.correlationId()).isEmpty();
        assertThat(RequestContext.asMap()).isEmpty();
    }

    @Test
    void openPublishesCorrelationIdAndExtrasToMdc() throws Exception {
        try (AutoCloseable scope = RequestContext.open(ID, Map.of("tenant", "dxb"))) {
            assertThat(RequestContext.correlationId()).contains(ID);
            assertThat(RequestContext.asMap())
                    .containsEntry("traceId", ID.value())
                    .containsEntry("tenant", "dxb");
            assertThat(MDC.get("traceId")).isEqualTo(ID.value());
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
        }
        assertThat(RequestContext.correlationId()).isEmpty();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("tenant")).isNull();
    }

    @Test
    void closeRestoresShadowedMdcValues() throws Exception {
        MDC.put("traceId", "preexisting");
        MDC.put("userKey", "kept");

        try (AutoCloseable scope = RequestContext.open(ID, Map.of())) {
            assertThat(MDC.get("traceId")).isEqualTo(ID.value());
        }

        assertThat(MDC.get("traceId")).isEqualTo("preexisting");
        assertThat(MDC.get("userKey")).isEqualTo("kept");
    }

    @Test
    void nestedScopesInnermostWinsAndUnwindExactly() throws Exception {
        CorrelationId inner = new CorrelationId("ffffffffffffffffffffffffffffffff");

        try (AutoCloseable outerScope = RequestContext.open(ID, Map.of("outer", "1"))) {
            try (AutoCloseable innerScope = RequestContext.open(inner, Map.of("inner", "2"))) {
                assertThat(RequestContext.correlationId()).contains(inner);
                assertThat(RequestContext.asMap())
                        .containsEntry("traceId", inner.value())
                        .containsEntry("outer", "1")
                        .containsEntry("inner", "2");
            }
            assertThat(RequestContext.correlationId()).contains(ID);
            assertThat(RequestContext.asMap())
                    .containsEntry("traceId", ID.value())
                    .containsEntry("outer", "1")
                    .doesNotContainKey("inner");
        }
        assertThat(RequestContext.correlationId()).isEmpty();
    }

    @Test
    void asMapIsAnImmutableSnapshot() throws Exception {
        try (AutoCloseable scope = RequestContext.open(ID, Map.of("tenant", "dxb"))) {
            Map<String, String> snapshot = RequestContext.asMap();

            assertThat(snapshot).isUnmodifiable();
        }
    }

    @Test
    void contextNeverLeaksAcrossThreads() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AutoCloseable scope = RequestContext.open(ID, Map.of("tenant", "dxb"))) {
            boolean otherThreadSeesNothing = executor
                    .submit(() -> RequestContext.correlationId().isEmpty() && RequestContext.asMap().isEmpty())
                    .get();

            assertThat(otherThreadSeesNothing)
                    .as("context opened on the test thread must be invisible to other threads")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void replaceIsolatesOuterScopesAndRestoresThemExactly() throws Exception {
        CorrelationId outerId = new CorrelationId("22222222222222222222222222222222");
        try (AutoCloseable outer = RequestContext.open(outerId, Map.of("tenant", "dxb"))) {
            try (AutoCloseable replaced = RequestContext.replace(ID, Map.of("channel", "batch"))) {
                assertThat(RequestContext.correlationId()).contains(ID);
                assertThat(RequestContext.asMap()).containsOnlyKeys("traceId", "channel");
                assertThat(MDC.get("tenant")).isNull();
                try (AutoCloseable nested = RequestContext.open(ID, Map.of("step", "2"))) {
                    // a normal scope inside an isolating one still merges with it, not with the outer
                    assertThat(RequestContext.asMap()).containsOnlyKeys("traceId", "channel", "step");
                }
            }
            assertThat(RequestContext.correlationId()).contains(outerId);
            assertThat(RequestContext.asMap()).containsEntry("tenant", "dxb").doesNotContainKey("channel");
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
            assertThat(MDC.get("traceId")).isEqualTo(outerId.value());
            assertThat(MDC.get("channel")).isNull();
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void replaceWithoutAnIdMeansNoCorrelationContext() throws Exception {
        try (AutoCloseable outer = RequestContext.open(ID, Map.of())) {
            try (AutoCloseable none = RequestContext.replace(null, Map.of())) {
                assertThat(RequestContext.correlationId()).isEmpty();
                assertThat(RequestContext.asMap()).isEmpty();
                assertThat(MDC.get("traceId")).isNull();
            }
            assertThat(MDC.get("traceId")).isEqualTo(ID.value());
        }
    }
}
