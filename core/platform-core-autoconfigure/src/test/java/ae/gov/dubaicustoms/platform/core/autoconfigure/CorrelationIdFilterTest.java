package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit behaviour of the correlation filter: the rejected-traceparent marker follows the propagator's
 * outcome (not a second set of rules) and never carries header content.
 */
class CorrelationIdFilterTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureFilterLog() {
        appender.start();
        filterLogger.addAppender(appender);
        filterLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        filterLogger.detachAppender(appender);
        filterLogger.setLevel(null);
    }

    // The tracer as the propagator left it: the current span's trace id.
    private static Tracer tracerWithCurrentTrace(String traceId) {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
        when(context.spanId()).thenReturn("b7ad6b7169203331");
        return tracer;
    }

    private Map<String, String> run(Tracer tracer, String traceparent) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        if (traceparent != null) {
            request.addHeader("traceparent", traceparent);
        }
        Map<String, String> seen = new HashMap<>();
        new CorrelationIdFilter(tracer).doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.putAll(RequestContext.asMap()));
        return seen;
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void aContinuedTraceIsNotMarkedAndOpensTheContext() throws Exception {
        Map<String, String> seen = run(tracerWithCurrentTrace(TRACE), "00-" + TRACE + "-b7ad6b7169203331-01");
        assertThat(seen).containsEntry("traceId", TRACE).containsEntry("spanId", "b7ad6b7169203331");
        assertThat(lines()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "deadbeef",                                                          // hex alphabet only
            "00-00000000000000000000000000000000-b7ad6b7169203331-01",           // all-zero trace id
            "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",           // all-zero parent id
            "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",           // forbidden version
            "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01"})          // upper case
    void aTraceparentThePropagatorDidNotContinueIsMarkedWithoutItsContent(String rejected) throws Exception {
        // Whatever the reason, the propagator started a new trace: that outcome is the rejection.
        run(tracerWithCurrentTrace("11111111111111111111111111111111"), rejected);
        assertThat(lines()).singleElement().isEqualTo(
                "rejected inbound traceparent (length=" + rejected.length() + "); a new trace was started");
    }

    @Test
    void hostileContentNeverReachesTheLog() throws Exception {
        String hostile = "00-\r\nX-Injected: forged-log-line\u0007" + "f".repeat(300);
        run(tracerWithCurrentTrace("11111111111111111111111111111111"), hostile);
        assertThat(lines()).singleElement().satisfies(line -> {
            assertThat(line).doesNotContain("\r", "\n", "\u0007", "X-Injected", "forged", "fff");
            assertThat(line).contains("length=" + hostile.length());
        });
    }

    @Test
    void noTracerMeansNoContextAndNoMarker() throws Exception {
        Map<String, String> seen = run(Tracer.NOOP, "garbage");
        assertThat(seen).isEmpty();
        assertThat(lines()).as("nothing to judge without a propagator").isEmpty();
    }

    @Test
    void aNon128BitTraceIdIsNotACorrelationId() throws Exception {
        Map<String, String> seen = run(tracerWithCurrentTrace("463ac35c9f6413ad"), null);
        assertThat(seen).isEmpty();
    }

    @Test
    void continuesComparesTheTraceIdFieldOnly() {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(TRACE);
        assertThat(CorrelationIdFilter.continues("00-" + TRACE + "-b7ad6b7169203331-01", context)).isTrue();
        assertThat(CorrelationIdFilter.continues("00-" + TRACE, context)).isTrue();
        assertThat(CorrelationIdFilter.continues("00-short", context)).isFalse();
        assertThat(CorrelationIdFilter.continues("00-" + TRACE, null)).isFalse();
    }
}
