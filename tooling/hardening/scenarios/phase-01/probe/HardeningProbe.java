package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Consumer-side probe for the hardening phase-01 harness (never part of a real service). Uses only the
 * platform's public API and documented extension points: {@link SecurityCustomizer} to open
 * {@code /probe/**}, {@link PlatformRestClientFactory} for the outbound call, {@link RequestContext}
 * to read the correlation id.
 */
public final class HardeningProbe {

    private HardeningProbe() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class ProbeConfiguration {

        @Bean
        SecurityCustomizer probePathsArePublic() {
            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/probe/**").permitAll());
        }
    }

    @Service
    public static class AsyncSnapshot {

        @Async
        public CompletableFuture<Map<String, String>> capture() {
            return CompletableFuture.completedFuture(snapshot());
        }
    }

    public record Applicant(@NotBlank String name, @Email String email) {
    }

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

        private final AsyncSnapshot async;
        private final RestClient self;

        public ProbeController(AsyncSnapshot async, PlatformRestClientFactory factory,
                WebServerApplicationContext context) {
            this.async = async;
            this.self = factory.builder("probe-self")
                    .baseUrl("http://127.0.0.1:" + context.getWebServer().getPort())
                    .build();
        }

        @GetMapping("/context")
        public Map<String, Object> context() throws Exception {
            Map<String, Object> body = new HashMap<>(snapshot());
            body.put("async", async.capture().get());
            log.info("probe context");
            return body;
        }

        @GetMapping("/outbound")
        public Map<String, Object> outbound() {
            Map<String, Object> body = new HashMap<>(snapshot());
            body.put("downstream", self.get().uri("/probe/headers").retrieve().body(Map.class));
            return body;
        }

        @GetMapping("/headers")
        public Map<String, String> headers(@RequestHeader Map<String, String> headers) {
            Map<String, String> lower = new HashMap<>();
            headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
            return lower;
        }

        @PostMapping("/applicants")
        public Map<String, String> apply(@Valid @RequestBody Applicant applicant) {
            return Map.of("accepted", applicant.name());
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("cannot reach jdbc:oracle:thin:@db-secret-host:1521/PRD");
        }

        @GetMapping("/slow")
        public Map<String, Object> slow(@RequestParam long ms) throws InterruptedException {
            Thread.sleep(ms);
            return Map.of("slept", ms);
        }
    }

    static Map<String, String> snapshot() {
        Map<String, String> values = new HashMap<>();
        RequestContext.correlationId().map(CorrelationId::value).ifPresent(id -> values.put("context", id));
        Optional.ofNullable(MDC.get("traceId")).ifPresent(id -> values.put("mdcTraceId", id));
        Optional.ofNullable(MDC.get("spanId")).ifPresent(id -> values.put("mdcSpanId", id));
        Optional.ofNullable(MDC.get("correlationId")).ifPresent(id -> values.put("mdcCorrelationId", id));
        values.put("thread", Thread.currentThread().getName());
        return values;
    }
}
