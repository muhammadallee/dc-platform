package ae.gov.dubaicustoms.platform.audit.log;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Outcome;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Verifies the sink emits one INFO record on the AUDIT logger carrying every event field. */
class LogAuditSinkTest {

    @Test
    void writesOneStructuredRecordToAuditLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger auditLogger = context.getLogger(LogAuditSink.AUDIT_LOGGER);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            LogAuditSink sink = new LogAuditSink();
            sink.write(new AuditEvent("order.create", "u1", "order:42", Outcome.SUCCESS,
                    Instant.parse("2026-01-01T00:00:00Z"), "cid-1", Map.of("total", 199.0)));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent record = appender.list.get(0);
            assertThat(record.getLevel()).isEqualTo(Level.INFO);
            String message = record.getFormattedMessage();
            assertThat(message)
                    .contains("action=order.create")
                    .contains("actor=u1")
                    .contains("resource=order:42")
                    .contains("outcome=SUCCESS")
                    .contains("correlationId=cid-1")
                    .contains("total=199.0");
        } finally {
            auditLogger.detachAppender(appender);
        }
    }
}
