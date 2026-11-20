package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rule 16 for logging and observability (Spring-native Phase 3, AC17/contract 14): an app holding only the platform's
 * core, logging and observability auto-configuration (plus Boot's web and actuator, as the archetype gives every
 * service), with no property set, logs one masked JSON line per event on the request's trace, and serves actuator
 * only on its own port (ND13). The one setting is {@code server.port=0}, which a test needs; the management port then
 * follows it to a random port.
 */
@ExtendWith(OutputCaptureExtension.class)
class ZeroConfigAcceptanceTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    // Boot copies logging settings into JVM system properties when logging starts.
    private final Properties systemProperties = (Properties) System.getProperties().clone();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @RestController
        static class Orders {

            private static final Logger log = LoggerFactory.getLogger(Orders.class);

            @GetMapping("/orders")
            String orders() {
                log.atInfo().addKeyValue("orderId", "42").addKeyValue("password", "hunter2")
                        .log("order placed, login password=hunter2");
                return "ok";
            }
        }
    }

    @AfterEach
    void restoreSystemProperties() {
        System.setProperties((Properties) systemProperties.clone());
    }

    @Test
    void anAppWithOnlyTheStartersLogsMaskedJsonAndServesActuatorOnItsOwnPort(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class).run("--server.port=0")) {
            String app = context.getEnvironment().getProperty("local.server.port");
            String management = context.getEnvironment().getProperty("local.management.port");
            assertThat(management).isNotNull().isNotEqualTo(app);

            HttpResponse<String> order = get(app, "/orders", "00-" + TRACE + "-00f067aa0ba902b7-01");
            assertThat(order.statusCode()).isEqualTo(200);

            assertThat(get(management, "/actuator/health/readiness", null).body()).contains("\"status\":\"UP\"");
            assertThat(get(management, "/actuator/prometheus", null).statusCode()).isEqualTo(200);
            for (String path : new String[] {"/actuator/health", "/actuator/info", "/actuator/prometheus"}) {
                assertThat(get(app, path, null).statusCode()).as(path).isEqualTo(404);
            }
        }
        String line = output.getOut().lines().filter(l -> l.contains("order placed")).findFirst()
                .orElseThrow(() -> new AssertionError("no order line"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = new JsonMapper().readValue(line, Map.class);
        assertThat(line).doesNotContain("hunter2");
        assertThat(event).containsEntry("message", "order placed, login password=****")
                .containsEntry("password", "****").containsEntry("orderId", "42").containsEntry("traceId", TRACE)
                .containsEntry("service", "application").containsKeys("@timestamp", "level", "logger", "spanId");
        assertThat(output.getOut().lines().filter(l -> !l.isBlank()))
                .as("every stdout line is JSON: no banner").allSatisfy(l -> assertThat(l).startsWith("{"));
    }

    private static HttpResponse<String> get(String port, String path, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
