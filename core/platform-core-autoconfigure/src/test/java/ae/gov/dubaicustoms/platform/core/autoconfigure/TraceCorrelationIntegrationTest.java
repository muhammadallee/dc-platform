package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.Filter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end correlation contract (phase-01 scope R1/R2) through a real embedded server, Boot's own
 * observation filter and the OpenTelemetry W3C propagator: nothing here parses {@code traceparent}
 * by hand except the test's assertions.
 */
class TraceCorrelationIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Records the MDC at the very edge of the servlet chain (before tracing, after everything), per dispatch. */
    static final ConcurrentLinkedQueue<Map<String, String>> EDGE_MDC = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> EDGE_DISPATCHES = new ConcurrentLinkedQueue<>();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableAsync
    static class App {

        @Bean
        FilterRegistrationBean<Filter> edgeMdcRecorder() {
            Filter filter = (request, response, chain) -> {
                EDGE_MDC.add(Optional.ofNullable(MDC.getCopyOfContextMap()).orElse(Map.of()));
                try {
                    chain.doFilter(request, response);
                } finally {
                    EDGE_MDC.add(Optional.ofNullable(MDC.getCopyOfContextMap()).orElse(Map.of()));
                    EDGE_DISPATCHES.add(request.getDispatcherType().name());
                }
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC,
                    jakarta.servlet.DispatcherType.ERROR);
            return registration;
        }

        // Inner rejection: sendError → a container ERROR dispatch the edge recorder also sees.
        @Bean
        FilterRegistrationBean<Filter> rejecting() {
            Filter filter = (request, response, chain) -> {
                if ("/reject".equals(((jakarta.servlet.http.HttpServletRequest) request).getRequestURI())) {
                    ((jakarta.servlet.http.HttpServletResponse) response).sendError(409);
                    return;
                }
                chain.doFilter(request, response);
            };
            return new FilterRegistrationBean<>(filter);
        }

        // Instrumented like any Boot-managed client: the observation registry adds traceparent.
        @Bean
        RestClient selfClient(ObservationRegistry observationRegistry) {
            return RestClient.builder().observationRegistry(observationRegistry).build();
        }
    }

    @Service
    static class AsyncProbe {

        @Async
        public CompletableFuture<Map<String, String>> capture() {
            return CompletableFuture.completedFuture(snapshot());
        }
    }

    @RestController
    static class ProbeController {

        private final AsyncProbe asyncProbe;
        private final RestClient selfClient;
        private final WebServerApplicationContext context;

        ProbeController(AsyncProbe asyncProbe, RestClient selfClient, WebServerApplicationContext context) {
            this.asyncProbe = asyncProbe;
            this.selfClient = selfClient;
            this.context = context;
        }

        @GetMapping("/probe")
        Map<String, Object> probe() throws Exception {
            Map<String, Object> body = new HashMap<>(snapshot());
            body.put("async", asyncProbe.capture().get());
            return body;
        }

        @GetMapping("/outbound")
        Map<String, Object> outbound() {
            Map<String, Object> body = new HashMap<>(snapshot());
            body.put("downstreamHeaders", selfClient.get()
                    .uri("http://127.0.0.1:" + context.getWebServer().getPort() + "/headers")
                    .retrieve().body(Map.class));
            return body;
        }

        @GetMapping("/callable")
        java.util.concurrent.Callable<Map<String, String>> callable() {
            return TraceCorrelationIntegrationTest::snapshot;
        }

        @GetMapping("/headers")
        Map<String, String> headers(@RequestHeader Map<String, String> headers) {
            Map<String, String> lowerCased = new HashMap<>();
            headers.forEach((name, value) -> lowerCased.put(name.toLowerCase(java.util.Locale.ROOT), value));
            return lowerCased;
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

    static String randomTraceId() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String traceparent(String traceId) {
        return "00-" + traceId + "-" + HexFormat.of().formatHex(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}) + "-01";
    }

    abstract static class RunningApp {

        ConfigurableApplicationContext context;
        int port;

        abstract String samplingProbability();

        @BeforeAll
        void start() {
            context = new SpringApplication(App.class, AsyncProbe.class, ProbeController.class)
                    .run("--server.port=0", "--spring.main.banner-mode=off",
                            "--management.tracing.sampling.probability=" + samplingProbability(),
                            "--spring.task.execution.pool.core-size=1", "--spring.task.execution.pool.max-size=1",
                            "--logging.level.root=WARN");
            port = ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        @AfterAll
        void stop() {
            if (context != null) {
                context.close();
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> get(String path, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            headers.forEach(request::header);
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("status of %s: %s", path, response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Correlation-Id")).as("no correlation header echoed").isEmpty();
            Map<String, Object> body = JSON.readValue(response.body(), Map.class);
            body.put("_responseHeaders", response.headers().map());
            return body;
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AlwaysSampled extends RunningApp {

        @Override
        String samplingProbability() {
            return "1.0";
        }

        @Test
        void validTraceparentIsContinuedAndIsTheCorrelationId() throws Exception {
            String traceId = randomTraceId();
            Map<String, Object> body = get("/probe", Map.of("traceparent", traceparent(traceId)));
            assertThat(body).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);
            assertThat(body.get("mdcSpanId")).asString().matches("[0-9a-f]{16}");
            assertThat(body).doesNotContainKey("mdcCorrelationId");
        }

        @Test
        void missingTraceparentStartsANewTrace() throws Exception {
            Map<String, Object> body = get("/probe", Map.of());
            assertThat(body.get("context")).asString().matches("[0-9a-f]{32}");
            assertThat(body.get("mdcTraceId")).isEqualTo(body.get("context"));
        }

        @Test
        void invalidTraceparentsStartANewTrace() throws Exception {
            for (String invalid : List.of("garbage", "00-" + "z".repeat(32) + "-0102030405060708-01",
                    "00-" + "0".repeat(32) + "-0102030405060708-01", "00-" + randomTraceId() + "-" + "a".repeat(300))) {
                Map<String, Object> body = get("/probe", Map.of("traceparent", invalid));
                assertThat(body.get("context")).as("for %s", invalid).asString().matches("[0-9a-f]{32}")
                        .isNotEqualTo("0".repeat(32));
                assertThat(invalid).doesNotContain(String.valueOf(body.get("context")));
            }
        }

        @Test
        void strayCorrelationHeaderIsIgnored() throws Exception {
            String traceId = randomTraceId();
            String stray = randomTraceId();
            Map<String, Object> body = get("/outbound",
                    Map.of("traceparent", traceparent(traceId), "X-Correlation-Id", stray));
            assertThat(body).containsEntry("context", traceId);
            @SuppressWarnings("unchecked")
            Map<String, String> downstream = (Map<String, String>) body.get("downstreamHeaders");
            assertThat(downstream).doesNotContainKey("x-correlation-id").doesNotContainValue(stray);
        }

        @Test
        void outboundCallCarriesTheSameTrace() throws Exception {
            String traceId = randomTraceId();
            Map<String, Object> body = get("/outbound", Map.of("traceparent", traceparent(traceId)));
            @SuppressWarnings("unchecked")
            Map<String, String> downstream = (Map<String, String>) body.get("downstreamHeaders");
            assertThat(downstream.get("traceparent")).startsWith("00-" + traceId + "-");
            assertThat(downstream).doesNotContainKey("x-correlation-id");
        }

        @Test
        void asyncWorkSeesTheCallersTraceAndPoolThreadsAreCleanAfterwards() throws Exception {
            String traceId = randomTraceId();
            Map<String, Object> body = get("/probe", Map.of("traceparent", traceparent(traceId)));
            @SuppressWarnings("unchecked")
            Map<String, String> async = (Map<String, String>) body.get("async");
            assertThat(async.get("thread")).isNotEqualTo(body.get("thread"));
            assertThat(async).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);

            // Same single pool thread, submitted from a thread with no context: nothing may linger.
            AsyncTaskExecutor executor = context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);
            Map<String, String> leftover = executor.submit(TraceCorrelationIntegrationTest::snapshot).get();
            assertThat(leftover).containsOnlyKeys("thread");
        }

        @Test
        void mdcIsEmptyAtTheEdgeOfEveryRequest() throws Exception {
            EDGE_MDC.clear();
            for (int i = 0; i < 20; i++) {
                get("/probe", Map.of("traceparent", traceparent(randomTraceId())));
            }
            assertThat(EDGE_MDC).hasSize(40).allSatisfy(mdc -> assertThat(mdc).isEmpty());
        }

        @Test
        void asyncAndErrorRedispatchesSeeTheTraceAndLeaveTheEdgesClean() throws Exception {
            EDGE_MDC.clear();
            EDGE_DISPATCHES.clear();
            String traceId = randomTraceId();
            Map<String, Object> body = get("/callable", Map.of("traceparent", traceparent(traceId)));
            assertThat(body).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);

            HttpRequest rejected = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/reject"))
                    .header("traceparent", traceparent(traceId)).build();
            assertThat(HTTP.send(rejected, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);

            // The client can see the response before the edge filter's finally block runs: wait (bounded)
            // until every dispatch has recorded its exit, then check that REQUEST, ASYNC and ERROR all ran
            // and every edge was clean.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while ((!EDGE_DISPATCHES.containsAll(List.of("REQUEST", "ASYNC", "ERROR"))
                    || EDGE_MDC.size() != EDGE_DISPATCHES.size() * 2) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(EDGE_DISPATCHES).contains("REQUEST", "ASYNC", "ERROR");
            assertThat(EDGE_MDC).hasSize(EDGE_DISPATCHES.size() * 2).allSatisfy(mdc -> assertThat(mdc).isEmpty());
        }

        @Test
        void twoHundredConcurrentRequestsNeverBleedContext() throws Exception {
            // Plan §3.11: concurrency tests must pass 3 consecutive runs, so the round repeats in-test.
            for (int round = 0; round < 3; round++) {
                try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<String> sent = java.util.stream.IntStream.range(0, 200).mapToObj(i -> randomTraceId()).toList();
                    List<Future<Map<String, Object>>> replies = sent.stream()
                            .map(id -> callers.submit(() -> get("/probe", Map.of("traceparent", traceparent(id)))))
                            .toList();
                    for (int i = 0; i < sent.size(); i++) {
                        Map<String, Object> body = replies.get(i).get();
                        String expected = sent.get(i);
                        assertThat(body).as("round %d request %d", round, i)
                                .containsEntry("context", expected).containsEntry("mdcTraceId", expected);
                        @SuppressWarnings("unchecked")
                        Map<String, String> async = (Map<String, String>) body.get("async");
                        assertThat(async).containsEntry("context", expected).containsEntry("mdcTraceId", expected);
                    }
                }
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class NeverSampled extends RunningApp {

        @Override
        String samplingProbability() {
            return "0.0";
        }

        @Test
        void unsampledRequestsStillCarryTheTraceIdInContextAndMdc() throws Exception {
            String traceId = randomTraceId();
            Map<String, Object> body = get("/probe",
                    Map.of("traceparent", "00-" + traceId + "-0102030405060708-00"));
            assertThat(body).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);

            Map<String, Object> fresh = get("/probe", Map.of());
            assertThat(fresh.get("context")).asString().matches("[0-9a-f]{32}");
            assertThat(Objects.equals(fresh.get("mdcTraceId"), fresh.get("context"))).isTrue();
        }
    }
}
