package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-side probe for the Phase 8 all-capability smoke matrix (never part of a real service): an authenticated request writes a
 * row and an outbox event in one transaction and calls a downstream; the event reaches a handler over RabbitMQ, which writes a row
 * and calls the downstream too. Public API and documented configuration only. Nothing is opened to anonymous callers.
 */
public final class AllCapsProbe {

    static final String PLACED = "dc.hh-all:order.placed";

    private AllCapsProbe() {
    }

    /** The event, version 1. */
    @EventType(value = "hh.all-order", version = 1)
    public record OrderPlaced(String ref) {
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
        public void place(String ref, String owner, String trace) {
            jdbc.update("insert into all_order (ref, owner, trace_id) values (?, ?, ?)", ref, owner, trace);
            outbox.publish(PLACED, EventEnvelope.of(new OrderPlaced(ref)).key(ref).build());
        }
    }

    @Service
    public static class Receiver {

        private final JdbcTemplate jdbc;
        private final CurrentUserAccessor users;
        private final PlatformRestClientFactory clients;
        private final String downstream;

        public Receiver(JdbcTemplate jdbc, CurrentUserAccessor users, PlatformRestClientFactory clients,
                @Value("${probe.baseurl}") String downstream) {
            this.jdbc = jdbc;
            this.users = users;
            this.clients = clients;
            this.downstream = downstream;
        }

        @EventHandler(destination = PLACED)
        public void onPlaced(EventEnvelope<OrderPlaced> event) {
            String principal = users.currentUser().map(CurrentUser::subject).orElse("none");
            String answer;
            try {
                answer = clients.builder("main").baseUrl(downstream).build().get().uri("/handler?ref={ref}", event.payload().ref())
                        .retrieve().body(String.class);
            } catch (RuntimeException failed) {
                answer = "failed";
            }
            jdbc.update("insert into all_received (event_id, ref, handler_trace, principal, downstream) values (?, ?, ?, ?, ?)",
                    event.eventId(), event.payload().ref(), MDC.get("traceId"), principal, answer);
        }
    }

    @RestController
    @RequestMapping("/all")
    public static class Api {

        private static final Logger log = LoggerFactory.getLogger(Api.class);

        private final Orders orders;
        private final JdbcTemplate jdbc;
        private final CurrentUserAccessor users;
        private final PlatformRestClientFactory clients;
        private final String downstream;

        public Api(Orders orders, JdbcTemplate jdbc, CurrentUserAccessor users, PlatformRestClientFactory clients,
                @Value("${probe.baseurl}") String downstream) {
            this.orders = orders;
            this.jdbc = jdbc;
            this.users = users;
            this.clients = clients;
            this.downstream = downstream;
        }

        /** Authenticated request, database write and outbox event, outbound call. */
        @PostMapping("/orders")
        public Map<String, Object> place(@RequestParam(required = false) String ref) {
            String subject = users.currentUser().map(CurrentUser::subject).orElseThrow();
            String trace = RequestContext.correlationId().map(CorrelationId::value).orElse("none");
            String id = ref == null ? UUID.randomUUID().toString() : ref;
            orders.place(id, subject, trace);
            log.info("order stored");
            String answer = clients.builder("main").baseUrl(downstream).build().get().uri("/x").retrieve().body(String.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subject", subject);
            out.put("trace", trace);
            out.put("ref", id);
            out.put("downstream", answer);
            out.put("subjectAfter", users.currentUser().map(CurrentUser::subject).orElse("none"));
            return out;
        }

        @GetMapping("/remote/{client}")
        public String remote(@PathVariable String client) {
            return clients.builder(client).baseUrl(downstream).build().get().uri("/x").retrieve().body(String.class);
        }

        @GetMapping("/count/{owner}")
        public int count(@PathVariable String owner) {
            Integer rows = jdbc.queryForObject("select count(*) from all_order where owner = ?", Integer.class, owner);
            return rows == null ? 0 : rows;
        }

        /** What the handler saw for refs starting with a prefix: deliveries, distinct events, and one row's details. */
        @GetMapping("/received/{prefix}")
        public Map<String, Object> received(@PathVariable String prefix) {
            return jdbc.queryForMap("select count(*) as total, count(distinct event_id) as distinct_events, max(handler_trace) as trace, "
                    + "max(principal) as principal, min(downstream) as downstream from all_received where ref like ?", prefix + "%");
        }
    }
}
