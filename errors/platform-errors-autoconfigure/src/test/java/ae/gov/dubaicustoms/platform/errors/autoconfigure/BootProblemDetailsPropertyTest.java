package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * A service that turns on Boot's own problem details ({@code spring.mvc.problemdetails.enabled=true}) still gets the
 * platform contract. Boot registers its {@code @Order(0)} {@code ProblemDetailsExceptionHandler} only when no
 * {@code ResponseEntityExceptionHandler} bean exists; the platform's delegate is one, so Boot's backs off. Without it,
 * Boot's advice would answer framework and {@code ErrorResponse} exceptions first: no {@code traceId}, and a service's
 * 5xx detail in the body (HI-0008).
 */
class BootProblemDetailsPropertyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = new SpringApplication(ErrorContractIntegrationTest.App.class).run("--server.port=0",
                "--spring.main.banner-mode=off", "--spring.mvc.problemdetails.enabled=true");
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(String path, int status) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readValue(response.body(), Map.class);
    }

    @Test
    void bootsHandlerBacksOffToThePlatformDelegate() {
        assertThat(context.getBeansOfType(ResponseEntityExceptionHandler.class)).containsOnlyKeys("platformFrameworkErrors");
    }

    @Test
    void aServiceServerErrorStillHasTheGenericDetailAndTheTrace() throws Exception {
        Map<String, Object> body = call("/service-5xx", 503);
        assertThat(body).containsEntry("code", "ORD-0503").containsEntry("detail", "An unexpected error occurred.");
        assertThat((String) body.get("traceId")).matches("[0-9a-f]{32}");
        assertThat(body.toString()).doesNotContain("ledger.internal");
    }

    @Test
    void aFrameworkRejectionStillHasThePlatformContract() throws Exception {
        Map<String, Object> body = call("/no-such-endpoint", 404);
        assertThat(body).containsEntry("code", "DC-CORE-0499").containsKey("timestamp").containsKey("retryable");
        assertThat((String) body.get("traceId")).matches("[0-9a-f]{32}");
    }
}
