package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
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
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
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

        // A consumer filter rejecting a request with sendError: the response is rendered by the
        // servlet error page (an ERROR dispatch), not by MVC.
        @Bean
        FilterRegistrationBean<Filter> duplicateRejectingFilter() {
            Filter filter = (request, response, chain) -> {
                if ("/probe/duplicate".equals(((HttpServletRequest) request).getRequestURI())) {
                    ((HttpServletResponse) response).sendError(409);
                    return;
                }
                chain.doFilter(request, response);
            };
            return new FilterRegistrationBean<>(filter);
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

    /** A consumer infrastructure failure: 5xx, so its message must never reach the response body. */
    static final class PricingUnavailable extends PlatformException {

        static final ErrorCode CODE = new ErrorCode("DC-PROBE-0500");

        PricingUnavailable() {
            super(CODE, "pricing cluster node pricing-7.internal refused the connection");
        }
    }

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

        private final AsyncSnapshot async;
        private final PlatformRestClientFactory factory;
        private final WebServerApplicationContext context;
        private volatile RestClient self;

        // The port is resolved on first use: touching the web server during context refresh starts its
        // connector early, and the regular start then fails with "port already in use".
        public ProbeController(AsyncSnapshot async, PlatformRestClientFactory factory,
                WebServerApplicationContext context) {
            this.async = async;
            this.factory = factory;
            this.context = context;
        }

        private RestClient self() {
            if (self == null) {
                self = factory.builder("probe-self")
                        .baseUrl("http://127.0.0.1:" + context.getWebServer().getPort())
                        .build();
            }
            return self;
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
            body.put("downstream", self().get().uri("/probe/headers").retrieve().body(Map.class));
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

        @GetMapping("/platform-failure")
        public String platformFailure() {
            throw new PricingUnavailable();
        }

        @GetMapping("/async-ok")
        public java.util.concurrent.Callable<Map<String, String>> asyncOk() {
            return HardeningProbe::snapshot;
        }

        @GetMapping("/async-fail")
        public CompletableFuture<String> asyncFail() {
            return CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("async worker reached db-secret-host");
            });
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("cannot reach jdbc:oracle:thin:@db-secret-host:1521/PRD");
        }

        @GetMapping("/slow")
        public Map<String, Object> slow(@RequestParam long ms) throws InterruptedException {
            log.info("slow request entered");
            Thread.sleep(ms);
            return Map.of("slept", ms);
        }
    }

    @RestController
    public static class SecuredController {

        @GetMapping("/secured/admin")
        @PreAuthorize("hasAuthority('SCOPE_admin')")
        public String admin() {
            return "admin";
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
