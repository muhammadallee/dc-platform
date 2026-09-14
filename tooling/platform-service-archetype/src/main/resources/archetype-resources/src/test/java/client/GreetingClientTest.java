#set( $featureSet = ",${features}," )
#if($featureSet.contains(",restclient,"))
package ${package}.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ${package}.HttpIntegrationTestSupport;
import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

/**
 * Drives {@link GreetingClient} — built by the platform's {@link PlatformRestClientFactory} — through
 * REAL calls to a loopback stub: URI composition, method, headers, JSON both ways, empty bodies, 4xx/5xx
 * mapping to {@link RemoteCallException}, a bounded read timeout, a refused connection, and the
 * inbound-request → application log → outbound-call chain (correlation id + bearer-token relay).
 */
@ExtendWith(OutputCaptureExtension.class)
class GreetingClientTest extends HttpIntegrationTestSupport {

    private static final Stub STUB = Stub.start();

    @Autowired
    GreetingClient client;

    @Autowired
    PlatformRestClientFactory factory;

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("app.greeting.base-url", STUB::baseUrl);
        registry.add("dc.platform.restclient.clients.greeting.read-timeout", () -> "500ms");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    @Test
    void getDecodesJsonAndComposesTheUriFromTheBaseUrl() throws Exception {
        STUB.respond(200, "{\"message\":\"Hi, bob\"}");

        GreetingClient.Greeting greeting = client.fetch("bob");

        assertThat(greeting).isEqualTo(new GreetingClient.Greeting("Hi, bob"));
        Recorded request = STUB.take();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/greetings/bob");
        assertThat(request.header("Accept")).contains("application/json");
        // Called outside any inbound request: no identity and no correlation to relay.
        assertThat(request.header("Authorization")).isNull();
        assertThat(request.header("X-Correlation-Id")).isNull();
    }

    @Test
    void postEncodesTheDtoAsJsonAndAcceptsAnEmptyNoContentResponse() throws Exception {
        STUB.respond(204, "");

        client.publish(new GreetingClient.Greeting("Hello from ${artifactId}"));

        Recorded request = STUB.take();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/greetings");
        assertThat(request.header("Content-Type")).startsWith("application/json");
        assertThat(JsonPath.<String>read(request.body(), "$.message")).isEqualTo("Hello from ${artifactId}");
    }

    @Test
    void emptySuccessBodyYieldsNull() throws Exception {
        STUB.respond(200, "");

        assertThat(client.fetch("nobody")).isNull();
    }

    @Test
    void clientErrorMapsToRemoteCallExceptionWithStatusAndBody() {
        STUB.respond(404, "{\"detail\":\"no such greeting\"}");

        assertThatThrownBy(() -> client.fetch("ghost"))
                .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.bodySnippet()).contains("no such greeting");
                });
    }

    @Test
    void serverErrorMapsToRemoteCallExceptionWithRemoteCorrelation() {
        String remoteId = CorrelationId.random().value();
        STUB.respond(503, "unavailable", Map.of("X-Correlation-Id", remoteId));

        assertThatThrownBy(() -> client.fetch("bob"))
                .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.remoteCorrelationId()).contains(remoteId);
                });
    }

    @Test
    void slowResponseFailsWithinTheConfiguredReadTimeout() {
        STUB.respondAfter(Duration.ofSeconds(3), 200, "{\"message\":\"too late\"}");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> client.fetch("slow")).isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void refusedConnectionFailsFast() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        var unreachable = factory.builder("greeting").baseUrl("http://127.0.0.1:" + closedPort).build();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> unreachable.get().uri("/greetings/x").retrieve().toBodilessEntity())
                        .isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void inboundCorrelationAndTokenFlowThroughTheLogToTheOutboundCall(CapturedOutput output) throws Exception {
        STUB.respond(200, "{\"message\":\"relayed\"}");
        String correlationId = CorrelationId.random().value();
        String token = ISSUER.token("alice");

        HttpResponse<String> response = get("/test-support/relay", token, correlationId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("relayed");
        Recorded outbound = STUB.take();
        assertThat(outbound.header("X-Correlation-Id")).isEqualTo(correlationId);
        assertThat(outbound.header("Authorization")).isEqualTo("Bearer " + token);
        List<Map<String, Object>> fetchEvents = output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(line -> JsonPath.parse(line).<Map<String, Object>>json())
                .filter(event -> "Fetching remote greeting".equals(event.get("message")))
                .toList();
        assertThat(fetchEvents).singleElement().satisfies(event ->
                assertThat(event).containsEntry("correlationId", correlationId));
        assertThat(output.getAll()).doesNotContain(token);
    }

    /** Test-only inbound endpoint (never part of the service): turns a real request into an outbound call. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RelayProbe {

        @Bean
        RelayController relayController(GreetingClient client) {
            return new RelayController(client);
        }
    }

    @RestController
    static class RelayController {

        private final GreetingClient client;

        RelayController(GreetingClient client) {
            this.client = client;
        }

        @GetMapping("/test-support/relay")
        String relay() {
            return client.fetch("relay").message();
        }
    }

    record Recorded(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    /** Loopback downstream: records each request and replays the next scripted response. */
    static final class Stub implements AutoCloseable {

        private final HttpServer server;
        private final BlockingQueue<Recorded> requests = new LinkedBlockingQueue<>();
        private volatile int status = 200;
        private volatile String body = "";
        private volatile Map<String, String> headers = Map.of();
        private volatile Duration delay = Duration.ZERO;

        private Stub(HttpServer server) {
            this.server = server;
        }

        static Stub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                // One thread per exchange: a deliberately slow reply must not queue the next test's request.
                server.setExecutor(Executors.newCachedThreadPool());
                Stub stub = new Stub(server);
                server.createContext("/", stub::handle);
                server.start();
                return stub;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            requests.clear();
            respond(200, "");
        }

        void respond(int status, String body) {
            respond(status, body, Map.of());
        }

        void respond(int status, String body, Map<String, String> headers) {
            this.delay = Duration.ZERO;
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        void respondAfter(Duration delay, int status, String body) {
            respond(status, body);
            this.delay = delay;
        }

        Recorded take() throws InterruptedException {
            Recorded recorded = requests.poll(5, TimeUnit.SECONDS);
            assertThat(recorded).as("stub received a request").isNotNull();
            return recorded;
        }

        private void handle(HttpExchange exchange) throws IOException {
            // Snapshot the script first: a delayed handler must not pick up the next test's response.
            int replyStatus = status;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            Map<String, String> replyHeaders = headers;
            Duration replyDelay = delay;
            try (exchange) {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        Map.copyOf(exchange.getRequestHeaders()), requestBody));
                if (!replyDelay.isZero()) {
                    try {
                        Thread.sleep(replyDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                replyHeaders.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                if (bytes.length > 0) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                }
                exchange.sendResponseHeaders(replyStatus, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
#end
