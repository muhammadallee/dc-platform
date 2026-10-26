package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Boots a real web application (EnvironmentPostProcessor included) and asserts the phase-02
 * observability surface end to end: minimal default exposure (D105), pod-local readiness with a
 * separate {@code dependencies} group (D6/D104), and the Prometheus scrape content.
 */
class PlatformEndpointWebTest {

    private static final Pattern LABEL_VALUE = Pattern.compile("=\"([^\"]*)\"");

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = App.class,
            properties = "spring.application.name=observability-web-test")
    class Defaults {

        @Autowired
        private Environment environment;

        @Test
        void onlyHealthInfoAndPrometheusAreExposed() throws Exception {
            assertThat(get(environment, "/actuator/health").statusCode()).isEqualTo(503); // db is DOWN
            assertThat(get(environment, "/actuator/info").statusCode()).isEqualTo(200);
            assertThat(get(environment, "/actuator/prometheus").statusCode()).isEqualTo(200);
            for (String hidden : new String[] {"platform", "metrics", "env", "beans", "heapdump", "configprops"}) {
                assertThat(get(environment, "/actuator/" + hidden).statusCode()).as(hidden).isEqualTo(404);
            }
        }

        @Test
        void aSharedDependencyOutageLeavesReadinessUp() throws Exception {
            assertThat(get(environment, "/actuator/health/readiness").body()).contains("\"status\":\"UP\"");
            assertThat(get(environment, "/actuator/health/liveness").body()).contains("\"status\":\"UP\"");
            HttpResponse<String> dependencies = get(environment, "/actuator/health/dependencies");
            assertThat(dependencies.statusCode()).isEqualTo(503);
            assertThat(dependencies.body()).contains("\"status\":\"DOWN\"");
        }

        @Test
        void scrapeCarriesCommonTagsAndNoHighCardinalityValues() throws Exception {
            get(environment, "/actuator/health/readiness");
            String scrape = get(environment, "/actuator/prometheus").body();
            assertThat(scrape).contains("service=\"observability-web-test\"").contains("platform_version=\"");
            Matcher labels = LABEL_VALUE.matcher(scrape);
            while (labels.find()) {
                assertThat(labels.group(1)).as("label value")
                        .doesNotMatch("[0-9a-f]{32}|[0-9a-f]{16}|[0-9a-f]{8}-[0-9a-f]{4}-.*|eyJ.*");
            }
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = App.class,
            properties = "management.endpoints.web.exposure.include=health,platform")
    class OptIn {

        @Autowired
        private Environment environment;

        @Test
        void platformEndpointServesTheCapabilityReportWhenExposed() throws Exception {
            HttpResponse<String> response = get(environment, "/actuator/platform");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"name\":\"observability\"").contains("\"status\":\"ACTIVE\"");
        }
    }

    private static HttpResponse<String> get(Environment environment, String path) throws Exception {
        // Plain JDK client: TestRestTemplate left spring-boot-test in Boot 4.
        String port = environment.getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        // Stands in for a shared database that is down: contributor name "db", like Boot's own.
        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.down().withDetail("reason", "simulated outage").build();
        }
    }
}
