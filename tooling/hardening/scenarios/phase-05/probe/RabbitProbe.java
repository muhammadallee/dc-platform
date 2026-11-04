package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-side probe for the hardening phase-05 harness (never part of a real service). Handlers record what they received
 * and the trace they ran on, so the scenario reads attempts, duplicates and trace continuity from tables. Public API and
 * documented configuration only.
 */
public final class RabbitProbe {

    static final String EXCHANGE = "dc.hh-rabbit";
    static final String PLACED = EXCHANGE + ":order.placed";
    static final String FLAKY = EXCHANGE + ":order.flaky";
    static final String SLOW = EXCHANGE + ":order.slow";
    static final String NAIVE = EXCHANGE + ":order.naive";
    static final String DEDUPED = EXCHANGE + ":order.deduped";

    static final AtomicBoolean FAILING = new AtomicBoolean(true);

    private RabbitProbe() {
    }

    /** The event, version 1. */
    @EventType(value = "hh.order", version = 1)
    public record OrderPlaced(String ref) {
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

        public Orders(JdbcTemplate jdbc, OutboxPublisher outbox) {
            this.jdbc = jdbc;
            this.outbox = outbox;
        }

        @Transactional
        public void place(String ref) {
            jdbc.update("insert into hh_order (ref) values (?)", ref);
            outbox.publish(PLACED, EventEnvelope.of(new OrderPlaced(ref)).key(ref).build());
        }
    }

    /** The pattern the platform documents: the id and the business write commit together, and a duplicate does nothing. */
    @Service
    public static class Deduplicator {

        private final JdbcTemplate jdbc;

        public Deduplicator(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        public void applyOnce(String eventId) {
            try {
                jdbc.update("insert into hh_processed (event_id) values (?)", eventId);
            } catch (DuplicateKeyException alreadyProcessed) {
                return;
            }
            jdbc.update("insert into hh_effect (consumer, event_id) values ('deduped', ?)", eventId);
        }
    }

    @Service
    public static class Receiver {

        private final JdbcTemplate jdbc;
        private final Deduplicator deduplicator;

        public Receiver(JdbcTemplate jdbc, Deduplicator deduplicator) {
            this.jdbc = jdbc;
            this.deduplicator = deduplicator;
        }

        @EventHandler(destination = PLACED)
        public void onPlaced(EventEnvelope<OrderPlaced> event) {
            record(event);
        }

        @EventHandler(destination = FLAKY)
        public void onFlaky(EventEnvelope<OrderPlaced> event) {
            jdbc.update("insert into hh_attempt (ref) values (?)", event.payload().ref());
            if (FAILING.get()) {
                throw new IllegalStateException("probe handler failure");
            }
            record(event);
        }

        @EventHandler(destination = SLOW)
        public void onSlow(EventEnvelope<OrderPlaced> event) throws InterruptedException {
            Thread.sleep(3000);
            record(event);
        }

        @EventHandler(destination = NAIVE)
        public void onNaive(EventEnvelope<OrderPlaced> event) {
            jdbc.update("insert into hh_effect (consumer, event_id) values ('naive', ?)", event.eventId());
        }

        @EventHandler(destination = DEDUPED)
        public void onDeduped(EventEnvelope<OrderPlaced> event) {
            deduplicator.applyOnce(event.eventId());
        }

        private void record(EventEnvelope<OrderPlaced> event) {
            jdbc.update("insert into hh_received (event_id, ref, traceparent, handler_trace, producer) values (?, ?, ?, ?, ?)",
                    event.eventId(), event.payload().ref(), event.headers().get("traceparent"), MDC.get("traceId"),
                    event.headers().get("producer"));
        }
    }

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private final Orders orders;
        private final EventPublisher publisher;

        public ProbeController(Orders orders, EventPublisher publisher) {
            this.orders = orders;
            this.publisher = publisher;
        }

        @PostMapping("/orders")
        public Map<String, Object> place(@RequestParam String ref) {
            orders.place(ref);
            return Map.of("ref", ref);
        }

        @PostMapping("/direct")
        public Map<String, Object> direct(@RequestParam String ref,
                @RequestParam(defaultValue = "order.placed") String routingKey,
                @RequestParam(required = false) String header, @RequestParam(defaultValue = "1") int times,
                @RequestParam(required = false) String eventId) {
            for (int i = 0; i < times; i++) {
                EventEnvelope.Builder<OrderPlaced> event = EventEnvelope.of(new OrderPlaced(i == 0 || eventId != null ? ref : ref + "-" + i));
                if (eventId != null) {
                    event.eventId(eventId);
                }
                if (header != null) {
                    event.header(header, "value");
                }
                publisher.publish(EXCHANGE + ":" + routingKey, event.build());
            }
            return Map.of("ref", ref);
        }

        @PostMapping("/failing")
        public Map<String, Object> failing(@RequestParam boolean on) {
            FAILING.set(on);
            return Map.of("failing", on);
        }
    }
}
