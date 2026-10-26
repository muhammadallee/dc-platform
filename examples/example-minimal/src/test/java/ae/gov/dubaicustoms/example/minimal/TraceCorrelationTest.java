package ae.gov.dubaicustoms.example.minimal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The minimal service (core + errors + logging starters only, no observability starter) still
 * correlates by the W3C trace ID: the core starter brings the tracer, so an inbound traceparent is
 * continued and a missing one starts a new trace, and both reach the problem body as traceId.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceCorrelationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void inboundTraceIsContinuedIntoTheProblemBody() throws Exception {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> response = get("/widgets/42", "00-" + traceId + "-0102030405060708-01");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.traceId")).isEqualTo(traceId);
        assertThat(response.headers().firstValue("X-Correlation-Id")).isEmpty();
    }

    @Test
    void missingTraceparentStartsANewTrace() throws Exception {
        HttpResponse<String> response = get("/widgets/42", null);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.traceId")).matches("[0-9a-f]{32}");
    }
}
