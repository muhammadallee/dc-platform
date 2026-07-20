package ae.gov.dubaicustoms.platform.openapi.autoconfigure;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boots a real web application with springdoc's webmvc integration on the classpath and asserts
 * the phase-05 acceptance surface: {@code /v3/api-docs} contains the platform ProblemDetail
 * schema and the bearer-jwt security scheme.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PlatformOpenApiDocsWebTest.App.class,
        properties = "spring.application.name=openapi-web-test")
class PlatformOpenApiDocsWebTest {

    @Autowired
    private Environment environment;

    @Test
    void apiDocsContainProblemDetailSchemaAndBearerScheme() throws Exception {
        String port = environment.getProperty("local.server.port");
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET().build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"ProblemDetail\"")
                .contains("\"bearer-jwt\"")
                .contains("\"bearerFormat\":\"JWT\"");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @RestController
        static class SampleController {

            @GetMapping("/widgets/{id}")
            String get() {
                return "widget";
            }
        }
    }
}
