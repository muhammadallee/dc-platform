package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventPublisher;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-side probe for the hardening phase-04 harness (never part of a real service). A business write and an outbox
 * publish in one transaction, and a handler that records every delivery, with its headers, in a table: the scenario
 * then counts lost and duplicate events directly. Public API and documented configuration only.
 */
public final class OutboxProbe {

    static final String DESTINATION = "dc.hh-outbox:order.placed";
    static final String RELAY_EVENT = "hh.relay";

    private OutboxProbe() {
    }

    /** The event. */
    public record OrderPlaced(String ref) {
    }

    /** A domain event the relay turns into an integration event, on destination "dc." + its type. */
    @EventType(RELAY_EVENT)
    public record RelayedOrder(String ref) implements DomainEvent {
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        SecurityCustomizer probePathsArePublic() {
            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/probe/**").permitAll());
        }
    }

    @Service
    public static class Orders {

        private final JdbcTemplate jdbc;
        private final OutboxPublisher outbox;
        private final DomainEventPublisher domainEvents;

        public Orders(JdbcTemplate jdbc, OutboxPublisher outbox, DomainEventPublisher domainEvents) {
            this.jdbc = jdbc;
            this.outbox = outbox;
            this.domainEvents = domainEvents;
        }

        @Transactional
        public void place(String ref, String destination, String header, boolean fail) {
            jdbc.update("insert into hh_order (ref) values (?)", ref);
            EventEnvelope.Builder<OrderPlaced> event = EventEnvelope.of(new OrderPlaced(ref)).key(ref);
            if (header != null) {
                event.header(header, "value");
            }
            outbox.publish(destination, event.build());
            if (fail) {
                throw new IllegalArgumentException("business failure");
            }
        }

        @Transactional
        public void placeMany(String prefix, int count) {
            for (int i = 0; i < count; i++) {
                String ref = prefix + "-" + i;
                jdbc.update("insert into hh_order (ref) values (?)", ref);
                outbox.publish(DESTINATION, EventEnvelope.of(new OrderPlaced(ref)).key(ref).build());
            }
        }

        @Transactional
        public void relay(String ref) {
            jdbc.update("insert into hh_order (ref) values (?)", ref);
            domainEvents.publish(new RelayedOrder(ref));
        }
    }

    /**
     * Records every delivery. The table has no key on purpose: a duplicate is a second row. {@code probe.receiver=false}
     * leaves an instance without handlers, so a database fault on it cannot pass for a lost event.
     */
    @Service
    @ConditionalOnProperty(name = "probe.receiver", matchIfMissing = true)
    public static class Receiver {

        private final JdbcTemplate jdbc;
        private final String instance;

        public Receiver(JdbcTemplate jdbc, @Value("${probe.instance:app}") String instance) {
            this.jdbc = jdbc;
            this.instance = instance;
        }

        @EventHandler(destination = DESTINATION)
        public void onOrder(EventEnvelope<OrderPlaced> event) {
            record(event.payload().ref(), event);
        }

        @EventHandler(destination = "dc." + RELAY_EVENT)
        public void onRelayed(EventEnvelope<RelayedOrder> event) {
            record(event.payload().ref(), event);
        }

        private void record(String ref, EventEnvelope<?> event) {
            Map<String, String> headers = event.headers();
            jdbc.update("insert into hh_received (event_id, ref, traceparent, principal, instance) values (?, ?, ?, ?, ?)",
                    event.eventId(), ref, headers.get("traceparent"), headers.get("principal"), instance);
        }
    }

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private final Orders orders;

        public ProbeController(Orders orders) {
            this.orders = orders;
        }

        @PostMapping("/orders")
        public Map<String, Object> place(@RequestParam String ref,
                @RequestParam(defaultValue = DESTINATION) String destination,
                @RequestParam(required = false) String header, @RequestParam(defaultValue = "false") boolean fail) {
            orders.place(ref, destination, header, fail);
            return Map.of("ref", ref);
        }

        @PostMapping("/orders/many")
        public Map<String, Object> placeMany(@RequestParam String prefix, @RequestParam int count) {
            orders.placeMany(prefix, count);
            return Map.of("count", count);
        }

        @PostMapping("/relay")
        public Map<String, Object> relay(@RequestParam String ref) {
            orders.relay(ref);
            return Map.of("ref", ref);
        }
    }
}
