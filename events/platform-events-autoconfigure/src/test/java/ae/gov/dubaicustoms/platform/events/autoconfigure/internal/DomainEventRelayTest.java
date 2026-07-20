package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.PayloadApplicationEvent;

class DomainEventRelayTest {

    @EventType("OrderPlaced")
    record OrderPlaced(String orderId) implements DomainEvent {}

    record UnannotatedEvent(String value) implements DomainEvent {}

    private static PayloadApplicationEvent<Object> payloadEvent(Object payload) {
        return new PayloadApplicationEvent<>(new Object(), payload);
    }

    @Test
    void republishesAnnotatedDomainEventsToDestinationPrefixPlusEventType() {
        List<Object[]> published = new ArrayList<>();
        var relay = new DomainEventRelay(
                (destination, event) -> published.add(new Object[] {destination, event}), new ImmediateDispatcher(), "dc.");

        relay.onApplicationEvent(payloadEvent(new OrderPlaced("o-1")));

        assertThat(published).hasSize(1);
        assertThat(published.get(0)[0]).isEqualTo("dc.OrderPlaced");
        assertThat(((EventEnvelope<?>) published.get(0)[1]).payload()).isEqualTo(new OrderPlaced("o-1"));
    }

    @Test
    void ignoresDomainEventsWithoutEventTypeAnnotation() {
        List<Object[]> published = new ArrayList<>();
        var relay = new DomainEventRelay(
                (destination, event) -> published.add(new Object[] {destination, event}), new ImmediateDispatcher(), "dc.");

        relay.onApplicationEvent(payloadEvent(new UnannotatedEvent("v")));

        assertThat(published).isEmpty();
    }

    @Test
    void ignoresNonDomainEventPayloads() {
        List<Object[]> published = new ArrayList<>();
        var relay = new DomainEventRelay(
                (destination, event) -> published.add(new Object[] {destination, event}), new ImmediateDispatcher(), "dc.");

        relay.onApplicationEvent(payloadEvent("not a domain event"));

        assertThat(published).isEmpty();
    }
}
