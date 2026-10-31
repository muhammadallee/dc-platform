package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What {@link OutboxPublisher} does with {@code dc.platform.messaging.outbox.enabled=false}: publishes after commit, so
 * application code keeps working but a crash between the commit and the send loses the event. The startup WARN says so.
 * Immediately when no transaction is active. Thread-safe.
 */
public final class AfterCommitOutboxPublisher implements OutboxPublisher {

    private final EventPublisher publisher;

    public AfterCommitOutboxPublisher(EventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public void publish(String destination, EventEnvelope<?> event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.publish(destination, event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publish(destination, event);
            }
        });
    }

    @Override
    public boolean canPublish() {
        return true;
    }
}
