package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rule 16, AC22: an app holding only core and errors (plus Boot's web, validation and tracing, as the archetype
 * gives every service), with no property set, starts and meets the contract: the trace is continued or started
 * (AC1, AC2), a malformed body and a validation failure are 400s with their codes (AC10, AC11), and an unexpected
 * exception is a generic 500 (AC12), every body carrying the request's trace.
 */
class ZeroConfigAcceptanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static ConfigurableApplicationContext context;
    private static int port;

    record Applicant(@NotBlank String name) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @RestController
        static class Endpoints {

            @GetMapping("/boom")
            String boom() {
                throw new IllegalStateException("reached db-secret-host");
            }

            @GetMapping("/paid")
            String paid() {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "order 42 is already paid");
                problem.setProperty("code", "ORD-0409");
                throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
            }

            @PostMapping("/applicants")
            String apply(@Valid @RequestBody Applicant applicant) {
                return applicant.name();
            }
        }
    }

    @BeforeAll
    static void start() {
        context = new SpringApplication(App.class).run("--server.port=0", "--spring.main.banner-mode=off");
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> send(HttpRequest.Builder request, int status) throws Exception {
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        return JSON.readValue(response.body(), Map.class);
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("traceparent", "00-" + TRACE + "-00f067aa0ba902b7-01");
    }

    private static HttpRequest.Builder post(String path, String json) {
        return request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
    }

    @Test
    void anUnexpectedExceptionIsAGeneric500OnTheCallersTrace() throws Exception {
        Map<String, Object> body = send(request("/boom"), 500);
        assertThat(body).containsEntry("code", "DC-CORE-0500").containsEntry("traceId", TRACE)
                .containsEntry("detail", "An unexpected error occurred.").containsEntry("retryable", false)
                .containsKey("timestamp");
        assertThat(body.toString()).doesNotContain("db-secret-host");
    }

    @Test
    void withoutATraceparentANewTraceIsStartedAndReported() throws Exception {
        Map<String, Object> body = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/boom")), 500);
        assertThat((String) body.get("traceId")).matches("[0-9a-f]{32}").isNotEqualTo(TRACE);
    }

    @Test
    void aServiceErrorKeepsItsCodeAndDetail() throws Exception {
        assertThat(send(request("/paid"), 409)).containsEntry("code", "ORD-0409")
                .containsEntry("detail", "order 42 is already paid").containsEntry("traceId", TRACE);
    }

    @Test
    void malformedAndInvalidBodiesAre400sWithTheirCodes() throws Exception {
        assertThat(send(post("/applicants", "{\"name\": [oops"), 400)).containsEntry("code", "DC-CORE-0410")
                .containsEntry("traceId", TRACE);
        Map<String, Object> invalid = send(post("/applicants", "{\"name\":\"\"}"), 400);
        assertThat(invalid).containsEntry("code", "DC-CORE-0400").containsEntry("traceId", TRACE);
        assertThat(invalid.get("errors").toString()).contains("name");
    }
}
