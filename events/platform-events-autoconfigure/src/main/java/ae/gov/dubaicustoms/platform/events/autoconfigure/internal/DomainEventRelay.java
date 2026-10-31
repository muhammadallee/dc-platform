package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import java.util.Objects;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Re-publishes {@code @EventType}-annotated {@link DomainEvent}s as integration events. With the transactional outbox
 * on and a transaction on its database, the event is written through {@link OutboxPublisher} inside that transaction, so
 * it commits with the business write. Otherwise it goes through {@link EventPublisher} after the enclosing transaction
 * commits (or immediately when none is active), and a crash between the commit and the send loses it — see
 * {@code EventsProperties.Relay}'s javadoc and {@code docs/decisions/decision-log.md} (D32).
 */
public final class DomainEventRelay implements ApplicationListener<PayloadApplicationEvent<?>> {

    private final EventPublisher eventPublisher;
    private final AfterCommitDispatcher dispatcher;
    private final String destinationPrefix;
    private final OutboxPublisher outbox;

    public DomainEventRelay(EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, String destinationPrefix) {
        this(eventPublisher, dispatcher, destinationPrefix, null);
    }

    /**
     * Creates the relay.
     *
     * @param eventPublisher publishes when the outbox cannot; never {@code null}
     * @param dispatcher runs the fallback publish after commit; never {@code null}
     * @param destinationPrefix prepended to the event type; never {@code null}
     * @param outbox the transactional outbox, or {@code null} without one
     */
    public DomainEventRelay(EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, String destinationPrefix,
            OutboxPublisher outbox) {
        this.outbox = outbox;
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.destinationPrefix = Objects.requireNonNull(destinationPrefix, "destinationPrefix must not be null");
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<?> event) {
        Object payload = event.getPayload();
        if (!(payload instanceof DomainEvent)) {
            return;
        }
        EventType annotation = payload.getClass().getAnnotation(EventType.class);
        if (annotation == null) {
            return;
        }
        String destination = destinationPrefix + annotation.value();
        EventEnvelope<Object> envelope = EventEnvelope.of(payload).build();
        if (outbox != null && outbox.canPublish()) {
            outbox.publish(destination, envelope);
            return;
        }
        dispatcher.dispatch(() -> eventPublisher.publish(destination, envelope));
    }
}
