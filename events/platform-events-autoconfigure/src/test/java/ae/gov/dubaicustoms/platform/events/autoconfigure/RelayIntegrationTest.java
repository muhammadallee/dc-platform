package ae.gov.dubaicustoms.platform.events.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventPublisher;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.inmemory.InMemoryEventTransport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * Proves the relay end to end: a domain event annotated {@code @EventType} is re-published as an
 * integration event over the real in-memory transport once {@code dc.platform.events.relay.enabled=true}.
 *
 * <p>Wires a hand-rolled {@code EventPublisher} directly over {@link InMemoryEventTransport}
 * rather than pulling in {@code platform-messaging-autoconfigure} — that module's optional
 * micrometer dependency isn't on this module's test classpath, and this is a relay test, not a
 * messaging-autoconfigure test.
 */
@SpringBootTest(classes = RelayIntegrationTest.App.class,
        properties = "dc.platform.events.relay.enabled=true")
class RelayIntegrationTest {

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private InMemoryEventTransport transport;

    @EventType("OrderPlaced")
    record OrderPlaced(String orderId) implements DomainEvent {}

    @Test
    void relaysTheDomainEventAsAnIntegrationEvent() {
        List<String> received = new CopyOnWriteArrayList<>();
        transport.subscribe("dc.OrderPlaced", "test-group",
                (key, value, headers) -> received.add(new String(value, StandardCharsets.UTF_8)));

        publisher.publish(new OrderPlaced("o-1"));
        transport.awaitIdle(Duration.ofSeconds(2));

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("o-1");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        InMemoryEventTransport inMemoryEventTransport() {
            return new InMemoryEventTransport();
        }

        @Bean
        EventPublisher eventPublisher(InMemoryEventTransport transport) {
            return (destination, event) -> transport.send(destination, null,
                    String.valueOf(event.payload()).getBytes(StandardCharsets.UTF_8), event.headers());
        }
    }
}
