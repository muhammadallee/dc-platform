package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.PayloadApplicationEvent;

/** With the outbox on and a transaction on its database, the relay no longer has the after-commit gap (D32). */
class DomainEventRelayOutboxTest {

    @EventType("order.placed")
    record OrderPlaced(String id, Instant occurredAt) implements DomainEvent {
    }

    private final List<EventEnvelope<?>> direct = new ArrayList<>();
    private final EventPublisher publisher = (destination, event) -> direct.add(event);

    private static final class RecordingOutbox implements OutboxPublisher {
        private final boolean available;
        private final List<String> destinations = new ArrayList<>();

        RecordingOutbox(boolean available) {
            this.available = available;
        }

        @Override
        public void publish(String destination, EventEnvelope<?> event) {
            destinations.add(destination);
        }

        @Override
        public boolean canPublish() {
            return available;
        }
    }

    private void relay(OutboxPublisher outbox) {
        new DomainEventRelay(publisher, Runnable::run, "dc.", outbox).onApplicationEvent(
                new PayloadApplicationEvent<>(this, new OrderPlaced("o-1", Instant.now())));
    }

    @Test
    void writesThroughTheOutboxInsideTheTransaction() {
        var outbox = new RecordingOutbox(true);

        relay(outbox);

        assertThat(outbox.destinations).containsExactly("dc.order.placed");
        assertThat(direct).isEmpty();
    }

    @Test
    void fallsBackToAfterCommitWhenTheOutboxCannotPublish() {
        var outbox = new RecordingOutbox(false);

        relay(outbox);

        assertThat(outbox.destinations).isEmpty();
        assertThat(direct).hasSize(1);
    }

    @Test
    void worksWithoutAnOutbox() {
        relay(null);

        assertThat(direct).hasSize(1);
    }
}
