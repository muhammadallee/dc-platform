package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Consumer-side probe for the hardening phase-07 harness (never part of a real service). Public API and documented configuration only. */
@RestController
@RequestMapping
public class SecurityProbe {

    private static final Logger log = LoggerFactory.getLogger(SecurityProbe.class);

    private final CurrentUserAccessor users;
    private final Worker worker;
    private final PlatformRestClientFactory clients;
    private final String downstream;

    public SecurityProbe(CurrentUserAccessor users, Worker worker, PlatformRestClientFactory clients,
            @Value("${probe.baseurl:http://localhost:9}") String downstream) {
        this.users = users;
        this.worker = worker;
        this.clients = clients;
        this.downstream = downstream;
    }

    @GetMapping("/probe/me")
    Map<String, Object> me() {
        CurrentUser user = users.currentUser().orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", user.subject());
        out.put("roles", new TreeSet<>(user.roles()));
        out.put("mdc", MDC.getCopyOfContextMap() == null ? Map.of() : new TreeSet<>(MDC.getCopyOfContextMap().keySet()));
        log.info("probe me handled");
        return out;
    }

    @GetMapping("/probe/async")
    Map<String, String> async() throws Exception {
        return Map.of("here", users.currentUser().map(CurrentUser::subject).orElse("none"), "there", worker.subject().get());
    }

    @PreAuthorize("hasRole('clerk')")
    @GetMapping("/probe/clerk")
    String clerk() {
        return "clerk";
    }

    @PreAuthorize("hasRole('approver')")
    @GetMapping("/probe/approver")
    String approver() {
        return "approver";
    }

    @PreAuthorize("hasAuthority('SCOPE_orders.read')")
    @GetMapping("/probe/scoped")
    String scoped() {
        return "scoped";
    }

    @GetMapping("/public/ping")
    String publicPing() {
        return "public";
    }

    @GetMapping("/custom/ping")
    String customPing() {
        return "custom";
    }

    @GetMapping("/closed/ping")
    String closedPing() {
        return "closed";
    }

    @GetMapping("/probe/slow")
    String slow(@RequestParam long ms) throws InterruptedException {
        Thread.sleep(ms);
        return "finished";
    }

    @GetMapping("/probe/call/{client}")
    String call(@PathVariable String client) {
        RestClient rest = clients.builder(client).baseUrl(downstream).build();
        return rest.get().uri("/x").retrieve().body(String.class);
    }

    /** The target is a JSON type, so the converter parses. */
    @GetMapping("/probe/parse")
    Map<?, ?> parse() {
        return clients.builder("parse").baseUrl(downstream).build().get().uri("/x").retrieve().body(Map.class);
    }

    @Component
    public static class Worker {

        private final CurrentUserAccessor users;

        public Worker(CurrentUserAccessor users) {
            this.users = users;
        }

        @Async
        public CompletableFuture<String> subject() {
            return CompletableFuture.completedFuture(users.currentUser().map(CurrentUser::subject).orElse("none"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class Setup {

        @Bean
        SecurityCustomizer customPath() {
            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/custom/**").permitAll());
        }

        @Bean
        @ConditionalOnProperty("probe.wildcard")
        SecurityCustomizer wildcard() {
            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/**").permitAll());
        }

        @Bean
        @ConditionalOnProperty(name = "probe.chain", havingValue = "narrow")
        SecurityFilterChain narrow(HttpSecurity http) throws Exception {
            return http.securityMatcher("/admin/**").authorizeHttpRequests(a -> a.anyRequest().authenticated()).build();
        }

        @Bean
        @ConditionalOnProperty(name = "probe.chain", havingValue = "full")
        SecurityFilterChain full(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/closed/**").permitAll()
                    .anyRequest().denyAll()).build();
        }
    }
}
