package ae.gov.dubaicustoms.hardening.probe;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Consumer-side probe for the logging and observability run (never part of a real service). It logs the way a
 * service does (SLF4J messages, SLF4J key-value pairs, its own MDC keys, exceptions) so the scenario can read the JSON
 * lines back, and calls out through Boot's {@code RestClient.Builder}. Spring, SLF4J and Micrometer types only
 * (Spring-native Phase 3, contract 12): the scenario fails if this file imports a platform type.
 */
public final class ObservabilityProbe {

    private ObservabilityProbe() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class ProbeConfiguration {

    }

    @Service
    public static class AsyncLogger {

        private static final Logger log = LoggerFactory.getLogger(AsyncLogger.class);

        @Async
        public CompletableFuture<String> logCorrelated(String expected) {
            log.info("correlated-async {}", expected);
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

        private final AsyncLogger async;
        private final RestClient.Builder builder;
        private final WebServerApplicationContext context;
        private final JdbcTemplate jdbc;
        private final Tracer tracer;
        private volatile RestClient self;

        public ProbeController(AsyncLogger async, RestClient.Builder builder, WebServerApplicationContext context,
                JdbcTemplate jdbc, Tracer tracer) {
            this.async = async;
            this.builder = builder;
            this.context = context;
            this.jdbc = jdbc;
            this.tracer = tracer;
        }

        // Resolved on first use: touching the web server during refresh starts its connector early.
        private RestClient self() {
            if (self == null) {
                self = builder.clone().baseUrl("http://127.0.0.1:" + context.getWebServer().getPort()).build();
            }
            return self;
        }

        /** AC2: user-controlled text in a message argument. */
        @GetMapping("/log")
        public Map<String, Object> logUserText(@RequestParam String msg) {
            log.info("user said {}", msg);
            return snapshot();
        }

        /** AC2/AC5: a hostile exception message; the platform error handler logs it with its stack trace. */
        @GetMapping("/hostile")
        public String hostile(@RequestParam String msg) {
            throw new IllegalStateException(msg);
        }

        /** AC5: credentials and PII in the message, in key-value pairs and in the MDC. */
        @GetMapping("/secrets")
        public Map<String, Object> secrets(@RequestHeader("Authorization") String authorization,
                @RequestParam Map<String, String> values) {
            MDC.put("session-token", values.get("mdcToken"));
            MDC.put("customerEmail", values.get("email"));
            try {
                log.info("secrets in text: header {} jwt {} basic {} password={} eid {} pan {} email {} phone {}",
                        authorization, values.get("jwt"), "Basic " + values.get("basic"), values.get("password"),
                        values.get("eid"), values.get("pan"), values.get("email"), values.get("phone"));
                log.atInfo()
                        .addKeyValue("authorization", authorization)
                        .addKeyValue("password", values.get("password"))
                        .addKeyValue("cardNumber", Long.parseLong(values.get("pan")))
                        .addKeyValue("contact", values.get("email"))
                        .log("secrets as key-value pairs");
                log.atInfo()
                        .addKeyValue("session", Map.of("token", values.get("mdcToken"), "region", "dxb"))
                        .addKeyValue("password", values.get("password") + " with spaces")
                        .addKeyValue("secret", values.get("password").getBytes(StandardCharsets.UTF_8))
                        .addKeyValue("token", true)
                        .log("secrets in other shapes");
                log.warn("secret in an exception", new IllegalArgumentException(
                        "login failed for password=" + values.get("password") + " with " + authorization));
                log.info("negatives epoch {} order {} trace {} uuid {} ts {} status {} duration {}ms",
                        values.get("epoch"), values.get("order"), values.get("trace"), values.get("uuid"),
                        values.get("ts"), 503, 1234);
            } finally {
                MDC.remove("session-token");
                MDC.remove("customerEmail");
            }
            return snapshot();
        }

        /** AC3: consumer MDC keys (one plain, one credential-named, one noise key). */
        @GetMapping("/mdc")
        public Map<String, Object> mdc(@RequestParam String tenant) {
            MDC.put("tenant", tenant);
            MDC.put("apiKey", "consumer-api-key-" + tenant);
            MDC.put("noise", "noise-" + tenant);
            try {
                log.info("mdc probe {}", tenant);
            } finally {
                MDC.remove("tenant");
                MDC.remove("apiKey");
                MDC.remove("noise");
            }
            return snapshot();
        }

        /** AC4: the message names the trace the tracer holds; the log line must agree. */
        @GetMapping("/correlated")
        public Map<String, Object> correlated() throws Exception {
            String expected = traceId().orElse("none");
            log.info("correlated {}", expected);
            Map<String, Object> body = snapshot();
            body.put("asyncThread", async.logCorrelated(expected).get());
            return body;
        }

        /** AC8: outbound calls to literal (non-templated) URIs. */
        @GetMapping("/fanout")
        public Map<String, Object> fanout(@RequestParam int n) {
            int ok = 0;
            for (int i = 0; i < n; i++) {
                self().get().uri("/probe/echo/" + i).retrieve().toBodilessEntity();
                ok++;
            }
            return Map.of("calls", ok);
        }

        @GetMapping("/echo/{id}")
        public String echo(@PathVariable String id) {
            return id;
        }

        /** AC11: inbound trace continued into an outbound call. */
        @GetMapping("/outbound")
        public Map<String, Object> outbound() {
            Map<String, Object> body = snapshot();
            body.put("downstream", self().get().uri("/probe/headers").retrieve().body(Map.class));
            return body;
        }

        @GetMapping("/headers")
        public Map<String, String> headers(@RequestHeader Map<String, String> headers) {
            Map<String, String> lower = new HashMap<>();
            headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
            return lower;
        }

        /** AC10: a request that needs the database. */
        @GetMapping("/db")
        public Map<String, Object> db() {
            Map<String, Object> body = snapshot();
            body.put("one", jdbc.queryForObject("select 1", Integer.class));
            return body;
        }

        /** AC10: a request that does not. */
        @GetMapping("/context")
        public Map<String, Object> context() {
            return snapshot();
        }

        private Optional<String> traceId() {
            return Optional.ofNullable(tracer.currentSpan()).map(Span::context).map(c -> c.traceId());
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> values = new HashMap<>();
            traceId().ifPresent(id -> values.put("context", id));
            Optional.ofNullable(MDC.get("traceId")).ifPresent(id -> values.put("mdcTraceId", id));
            Optional.ofNullable(MDC.get("spanId")).ifPresent(id -> values.put("mdcSpanId", id));
            values.put("thread", Thread.currentThread().getName());
            return values;
        }
    }
}
