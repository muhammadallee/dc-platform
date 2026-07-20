package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventPublisher;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;

/** Default {@link DomainEventPublisher}: delegates straight to Spring's {@code ApplicationEventPublisher}. */
public final class DefaultDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public DefaultDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = Objects.requireNonNull(applicationEventPublisher, "applicationEventPublisher must not be null");
    }

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        applicationEventPublisher.publishEvent(event);
    }
}
