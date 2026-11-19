package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcContextTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void emptyWhenNoContextIsOpen() {
        assertThat(MdcContext.traceId()).isEmpty();
        assertThat(MdcContext.asMap()).isEmpty();
    }

    @Test
    void openPublishesCorrelationIdAndExtrasToMdc() throws Exception {
        try (AutoCloseable scope = MdcContext.open(ID, Map.of("tenant", "dxb"))) {
            assertThat(MdcContext.traceId()).contains(ID);
            assertThat(MdcContext.asMap())
                    .containsEntry("traceId", ID)
                    .containsEntry("tenant", "dxb");
            assertThat(MDC.get("traceId")).isEqualTo(ID);
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
        }
        assertThat(MdcContext.traceId()).isEmpty();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("tenant")).isNull();
    }

    @Test
    void closeRestoresShadowedMdcValues() throws Exception {
        MDC.put("traceId", "preexisting");
        MDC.put("userKey", "kept");

        try (AutoCloseable scope = MdcContext.open(ID, Map.of())) {
            assertThat(MDC.get("traceId")).isEqualTo(ID);
        }

        assertThat(MDC.get("traceId")).isEqualTo("preexisting");
        assertThat(MDC.get("userKey")).isEqualTo("kept");
    }

    @Test
    void nestedScopesInnermostWinsAndUnwindExactly() throws Exception {
        String inner = "ffffffffffffffffffffffffffffffff";

        try (AutoCloseable outerScope = MdcContext.open(ID, Map.of("outer", "1"))) {
            try (AutoCloseable innerScope = MdcContext.open(inner, Map.of("inner", "2"))) {
                assertThat(MdcContext.traceId()).contains(inner);
                assertThat(MdcContext.asMap())
                        .containsEntry("traceId", inner)
                        .containsEntry("outer", "1")
                        .containsEntry("inner", "2");
            }
            assertThat(MdcContext.traceId()).contains(ID);
            assertThat(MdcContext.asMap())
                    .containsEntry("traceId", ID)
                    .containsEntry("outer", "1")
                    .doesNotContainKey("inner");
        }
        assertThat(MdcContext.traceId()).isEmpty();
    }

    @Test
    void asMapIsAnImmutableSnapshot() throws Exception {
        try (AutoCloseable scope = MdcContext.open(ID, Map.of("tenant", "dxb"))) {
            Map<String, String> snapshot = MdcContext.asMap();

            assertThat(snapshot).isUnmodifiable();
        }
    }

    @Test
    void contextNeverLeaksAcrossThreads() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AutoCloseable scope = MdcContext.open(ID, Map.of("tenant", "dxb"))) {
            boolean otherThreadSeesNothing = executor
                    .submit(() -> MdcContext.traceId().isEmpty() && MdcContext.asMap().isEmpty())
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
        String outerId = "22222222222222222222222222222222";
        try (AutoCloseable outer = MdcContext.open(outerId, Map.of("tenant", "dxb"))) {
            try (AutoCloseable replaced = MdcContext.replace(ID, Map.of("channel", "batch"))) {
                assertThat(MdcContext.traceId()).contains(ID);
                assertThat(MdcContext.asMap()).containsOnlyKeys("traceId", "channel");
                assertThat(MDC.get("tenant")).isNull();
                try (AutoCloseable nested = MdcContext.open(ID, Map.of("step", "2"))) {
                    // a normal scope inside an isolating one still merges with it, not with the outer
                    assertThat(MdcContext.asMap()).containsOnlyKeys("traceId", "channel", "step");
                }
            }
            assertThat(MdcContext.traceId()).contains(outerId);
            assertThat(MdcContext.asMap()).containsEntry("tenant", "dxb").doesNotContainKey("channel");
            assertThat(MDC.get("tenant")).isEqualTo("dxb");
            assertThat(MDC.get("traceId")).isEqualTo(outerId);
            assertThat(MDC.get("channel")).isNull();
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void replaceWithoutAnIdMeansNoCorrelationContext() throws Exception {
        try (AutoCloseable outer = MdcContext.open(ID, Map.of())) {
            try (AutoCloseable none = MdcContext.replace(null, Map.of())) {
                assertThat(MdcContext.traceId()).isEmpty();
                assertThat(MdcContext.asMap()).isEmpty();
                assertThat(MDC.get("traceId")).isNull();
            }
            assertThat(MDC.get("traceId")).isEqualTo(ID);
        }
    }
}
