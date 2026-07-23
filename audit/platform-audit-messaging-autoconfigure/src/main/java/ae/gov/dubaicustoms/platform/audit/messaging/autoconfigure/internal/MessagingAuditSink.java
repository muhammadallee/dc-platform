package ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;

/**
 * {@link AuditSink} that publishes each event to the messaging transport on the {@code dc.audit}
 * destination, so audit records flow onto the same event backbone as domain events (for a central
 * audit consumer, SIEM bridge, or archival topic).
 *
 * <p>Runs on the audit worker; {@code publish} blocks until the transport acknowledges. Thread-safe;
 * a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public final class MessagingAuditSink implements AuditSink {

    /** Destination audit events are published to. */
    public static final String DESTINATION = "dc.audit";

    private final EventPublisher publisher;

    /**
     * @param publisher the messaging publisher audit events are sent through
     */
    public MessagingAuditSink(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void write(AuditEvent event) {
        publisher.publish(DESTINATION, event);
    }
}
