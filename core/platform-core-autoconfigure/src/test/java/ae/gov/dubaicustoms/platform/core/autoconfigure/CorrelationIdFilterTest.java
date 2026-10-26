package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Unit behaviour of the rejected-traceparent marker: sanitized, capped, never the raw value. */
class CorrelationIdFilterTest {

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

    @Test
    void sanitizeReplacesEveryCharacterOutsideTheTraceparentAlphabet() {
        assertThat(CorrelationIdFilter.sanitize("00-ab\r\nInjected: yes"))
                .hasSize("00-ab\r\nInjected: yes".length())
                .startsWith("00-ab??")
                .matches("[0-9a-f?-]+");
        assertThat(CorrelationIdFilter.sanitize("<script>")).isEqualTo("??c?????");
    }

    @Test
    void sanitizeCapsTheLength() {
        String oversized = "a".repeat(5_000);
        assertThat(CorrelationIdFilter.sanitize(oversized)).hasSize(64 + 3).endsWith("...");
    }

    @Test
    void sanitizeKeepsAValidTraceparentReadable() {
        String valid = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        assertThat(CorrelationIdFilter.sanitize(valid)).isEqualTo(valid);
    }

    @Test
    void rejectedMarkerNeverContainsTheRawValue() throws Exception {
        String hostile = "00-\r\nX-Injected: forged-log-line\u0007" + "f".repeat(300);
        CorrelationIdFilter filter = new CorrelationIdFilter(io.micrometer.tracing.Tracer.NOOP);
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/x");
        request.addHeader("traceparent", hostile);
        filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(), (req, res) -> {
        });

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line).startsWith("rejected inbound traceparent (length=" + hostile.length() + ",");
            assertThat(line).doesNotContain("\r", "\n", "\u0007", "X-Injected", "forged-log-line");
            assertThat(line.length()).isLessThan(200);
        });
    }

    @Test
    void validTraceparentProducesNoMarker() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(io.micrometer.tracing.Tracer.NOOP);
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/x");
        request.addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(), (req, res) -> {
        });
        assertThat(appender.list).isEmpty();
    }

    @Test
    void noSpanMeansNoContextAndThePassThroughStillRuns() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(io.micrometer.tracing.Tracer.NOOP);
        Map<String, Boolean> ran = new java.util.HashMap<>();
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/x"),
                new org.springframework.mock.web.MockHttpServletResponse(),
                (req, res) -> ran.put("chain", ae.gov.dubaicustoms.platform.core.context.RequestContext
                        .correlationId().isEmpty()));
        assertThat(ran).containsEntry("chain", true);
    }
}
