package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Boots a real web application (EnvironmentPostProcessor included) and asserts the phase-05
 * observability surface: /actuator/platform serves the capability report and the liveness/
 * readiness health groups exist — proving the exposure and health-group DEFAULTS work end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PlatformEndpointWebTest.App.class,
        properties = "spring.application.name=observability-web-test")
class PlatformEndpointWebTest {

    @Autowired
    private Environment environment;

    @Test
    void platformEndpointServesTheCapabilityReport() throws Exception {
        HttpResponse<String> response = get("/actuator/platform");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"name\":\"observability\"")
                .contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void readinessGroupExistsWithoutAnyUserConfiguration() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessGroupExistsWithoutAnyUserConfiguration() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
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
    }
}
