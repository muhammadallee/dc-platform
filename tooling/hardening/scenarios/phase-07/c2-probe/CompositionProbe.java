package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Consumer-side probe for composition checkpoint C2 (never part of a real service): a principal, a database write and an outbound call in one request. */
@RestController
public class CompositionProbe {

    private static final Logger log = LoggerFactory.getLogger(CompositionProbe.class);

    private final CurrentUserAccessor users;
    private final JdbcTemplate jdbc;
    private final PlatformRestClientFactory clients;
    private final String downstream;

    public CompositionProbe(CurrentUserAccessor users, JdbcTemplate jdbc, PlatformRestClientFactory clients,
            @Value("${probe.baseurl}") String downstream) {
        this.users = users;
        this.jdbc = jdbc;
        this.clients = clients;
        this.downstream = downstream;
    }

    /** Authenticated request, database write, outbound call: the chain of plan §9, C2. */
    @PostMapping("/c2/orders")
    Map<String, Object> order() {
        String subject = users.currentUser().map(CurrentUser::subject).orElseThrow();
        String trace = RequestContext.correlationId().map(CorrelationId::value).orElse("none");
        jdbc.update("insert into c2_order (owner, trace_id) values (?, ?)", subject, trace);
        log.info("order stored");
        String answer = clients.builder("main").baseUrl(downstream).build().get().uri("/x").retrieve().body(String.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", subject);
        out.put("trace", trace);
        out.put("downstream", answer);
        // Read again after the call, on the same thread: no other principal may have taken its place.
        out.put("subjectAfter", users.currentUser().map(CurrentUser::subject).orElse("none"));
        return out;
    }

    @GetMapping("/c2/remote/{client}")
    String remote(@PathVariable String client) {
        return clients.builder(client).baseUrl(downstream).build().get().uri("/x").retrieve().body(String.class);
    }

    @GetMapping("/c2/count/{owner}")
    int count(@PathVariable String owner) {
        Integer rows = jdbc.queryForObject("select count(*) from c2_order where owner = ?", Integer.class, owner);
        return rows == null ? 0 : rows;
    }

    @GetMapping("/c2/trace/{trace}")
    String ownerOfTrace(@PathVariable String trace) {
        return jdbc.queryForObject("select max(owner) from c2_order where trace_id = ?", String.class, trace);
    }
}
