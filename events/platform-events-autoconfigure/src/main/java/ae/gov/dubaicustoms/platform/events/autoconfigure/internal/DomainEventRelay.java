package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import java.util.Objects;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Re-publishes {@code @EventType}-annotated {@link DomainEvent}s as integration events via the
 * messaging capability's {@link EventPublisher}, after the enclosing transaction commits (or
 * immediately when none is active).
 *
 * <p>This is a lightweight outbox precursor, not a true transactional outbox — see
 * {@code EventsProperties.Relay}'s javadoc and {@code docs/decisions/decision-log.md}.
 */
public final class DomainEventRelay implements ApplicationListener<PayloadApplicationEvent<?>> {

    private final EventPublisher eventPublisher;
    private final AfterCommitDispatcher dispatcher;
    private final String destinationPrefix;

    public DomainEventRelay(EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, String destinationPrefix) {
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
        dispatcher.dispatch(() -> eventPublisher.publish(destination, EventEnvelope.of(payload).build()));
    }
}
