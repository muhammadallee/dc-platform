package ae.gov.dubaicustoms.example.minimal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One request, real tracing, the minimal starters only (phase-01 AC1/AC2/AC12 at slice level): the trace
 * ID in a 5xx problem body is the trace ID of that request's own ERROR log event, which carries the full
 * stack trace — for an unknown exception and for a 5xx {@link PlatformException}, on a continued and on
 * a freshly started trace. The body itself never carries the exception message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ErrorLogCorrelationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingEndpoints {

        @Bean
        Failing failing() {
            return new Failing();
        }
    }

    @RestController
    static class Failing {

        static final ErrorCode PRICING_UNAVAILABLE = new ErrorCode("DC-XMPL-0500");

        @GetMapping("/test/unknown")
        String unknown() {
            throw new IllegalStateException("cannot reach db-secret-host");
        }

        @GetMapping("/test/platform")
        String platform() {
            throw new PlatformException(PRICING_UNAVAILABLE, "pricing-7.internal refused") { };
        }
    }

    private HttpResponse<String> get(String path, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<Map<String, Object>> errorEvents(CapturedOutput output, String traceId) {
        return output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(line -> JsonPath.parse(line).<Map<String, Object>>json())
                .filter(event -> "ERROR".equals(event.get("level")) && traceId.equals(event.get("traceId")))
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test/unknown", "/test/platform"})
    void continuedTraceLinksTheBodyToItsOwnLogEvent(String path, CapturedOutput output) throws Exception {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> response = get(path, "00-" + traceId + "-0102030405060708-01");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(JsonPath.<String>read(response.body(), "$.traceId")).isEqualTo(traceId);
        assertThat(JsonPath.<String>read(response.body(), "$.detail")).isEqualTo("An unexpected error occurred.");
        assertThat(response.body()).doesNotContain("db-secret-host", "pricing-7");
        assertThat(errorEvents(output, traceId)).singleElement().satisfies(event ->
                assertThat((String) event.get("stack_trace")).containsAnyOf("db-secret-host", "pricing-7.internal"));
    }

    @Test
    void freshTraceLinksTheBodyToItsOwnLogEvent(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/test/unknown", null);

        String traceId = JsonPath.read(response.body(), "$.traceId");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(errorEvents(output, traceId)).singleElement().satisfies(event ->
                assertThat((String) event.get("stack_trace")).contains("db-secret-host"));
    }
}
