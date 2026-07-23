package ae.gov.dubaicustoms.platform.audit.log;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AuditSink} that writes each event as one line to a dedicated {@code AUDIT} logger, at
 * {@code INFO}. Because the message carries every field as a {@code key=value} pair, the platform's
 * JSON logging pipeline renders a structured audit record with no extra infrastructure, and operators
 * can route the {@code AUDIT} logger to its own appender/file.
 *
 * <p>This is the always-available default and the last link in the messaging &rarr; jdbc &rarr; log
 * degradation chain: it has no external dependency that can be unavailable at startup.
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public final class LogAuditSink implements AuditSink {

    /** Dedicated logger name so the audit trail can be routed independently of application logs. */
    public static final String AUDIT_LOGGER = "AUDIT";

    private final Logger log;

    /** Creates a sink writing to the {@code AUDIT} logger. */
    public LogAuditSink() {
        this(LoggerFactory.getLogger(AUDIT_LOGGER));
    }

    /**
     * @param log the logger to write to; package-private constructor injects a test logger
     */
    LogAuditSink(Logger log) {
        this.log = log;
    }

    @Override
    public void write(AuditEvent event) {
        log.info("audit action={} actor={} resource={} outcome={} correlationId={} details={}",
                event.action(), event.actor(), event.resource(), event.outcome(),
                event.correlationId(), event.details());
    }
}
