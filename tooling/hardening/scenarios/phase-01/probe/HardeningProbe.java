package ae.gov.dubaicustoms.hardening.probe;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Consumer-side probe for the core + errors consumer run (never part of a real service). Spring types only
 * (Spring-native Phase 2, contract 12): it reads the trace from Micrometer's {@link Tracer}, throws Spring's
 * {@link ErrorResponseException} with its own {@code code}, adds to every error body through a Spring
 * {@link ErrorResponse.Interceptor}, and calls out through Boot's {@link RestClient.Builder}.
 */
public final class HardeningProbe {

    // Set once by the controller; read by snapshot(), which also runs on @Async and async-MVC threads.
    private static volatile Tracer tracer;

    private HardeningProbe() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class ProbeConfiguration {

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

        // Every error body names the exception the interceptor was given: proves it sees the original.
        @Bean
        WebMvcConfigurer probeErrorInterceptor() {
            return new WebMvcConfigurer() {
                @Override
                public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
                    interceptors.add((detail, response) -> {
                        Throwable cause = response instanceof ErrorResponseException e ? e.getCause() : null;
                        detail.setProperty("seenBy", "probe:" + (cause == null ? "none" : cause.getClass().getSimpleName()));
                    });
                }
            };
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

    /** A consumer infrastructure failure: 5xx, so its detail must never reach the response body. */
    static final class PricingUnavailable extends ErrorResponseException {

        PricingUnavailable() {
            super(HttpStatus.INTERNAL_SERVER_ERROR, body(), null);
        }

        private static ProblemDetail body() {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    "pricing cluster node pricing-7.internal refused the connection");
            problem.setProperty("code", "DC-PROBE-0500");
            return problem;
        }
    }

    /** A business outcome declared with Spring's annotation: must be its status, not a 500. */
    @ResponseStatus(code = HttpStatus.CONFLICT, reason = "order already shipped")
    static final class AlreadyShipped extends RuntimeException {
    }

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

        private final AsyncSnapshot async;
        private final RestClient.Builder builder;
        private final WebServerApplicationContext context;
        private volatile RestClient self;

        // The port is resolved on first use: touching the web server during context refresh starts its
        // connector early, and the regular start then fails with "port already in use".
        public ProbeController(AsyncSnapshot async, RestClient.Builder builder, WebServerApplicationContext context,
                Tracer tracer) {
            this.async = async;
            this.builder = builder;
            this.context = context;
            HardeningProbe.tracer = tracer;
        }

        private RestClient self() {
            if (self == null) {
                self = builder.clone().baseUrl("http://127.0.0.1:" + context.getWebServer().getPort()).build();
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

        @GetMapping("/service-error")
        public String serviceError() {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "applicant 7 is already registered");
            problem.setProperty("code", "PROBE-0409");
            throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
        }

        @GetMapping("/annotated")
        public String annotated() {
            throw new AlreadyShipped();
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

        @GetMapping("/async-timeout")
        public org.springframework.web.context.request.async.DeferredResult<String> asyncTimeout() {
            return new org.springframework.web.context.request.async.DeferredResult<>(200L); // never completed
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

    // "context": the trace as a service sees it, from the tracer's current span.
    static Map<String, String> snapshot() {
        Map<String, String> values = new HashMap<>();
        Tracer current = tracer;
        Span span = current == null ? null : current.currentSpan();
        if (span != null && !span.context().traceId().isEmpty()) {
            values.put("context", span.context().traceId());
        }
        Optional.ofNullable(MDC.get("traceId")).ifPresent(id -> values.put("mdcTraceId", id));
        Optional.ofNullable(MDC.get("spanId")).ifPresent(id -> values.put("mdcSpanId", id));
        Optional.ofNullable(MDC.get("correlationId")).ifPresent(id -> values.put("mdcCorrelationId", id));
        values.put("thread", Thread.currentThread().getName());
        return values;
    }
}
