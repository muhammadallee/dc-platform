package ${package};

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for tests that drive the service over REAL HTTP on a random port. Every subclass shares one
 * application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class HttpIntegrationTestSupport {

    /**
     * A JWT-shaped bearer value. The service does not validate it (no web security in iteration 1); tests
     * send it to prove the platform's log masking keeps credentials out of the log.
     */
    protected static final String SAMPLE_BEARER =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2FtcGxlLXNpZ25hdHVyZS1ub3QtcmVhbA";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    protected int port;

    /**
     * GET {@code path}; {@code bearer} and {@code traceId} are optional (null = header omitted). A
     * {@code traceId} is sent the standard way, as a W3C {@code traceparent}: the platform continues
     * that trace, and its trace ID is the request's correlation id in logs and error bodies.
     */
    protected HttpResponse<String> get(String path, String bearer, String traceId)
            throws IOException, InterruptedException {
        return HTTP.send(request(path, bearer, traceId), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpRequest request(String path, String bearer, String traceId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (traceId != null) {
            builder.header("traceparent", traceparent(traceId));
        }
        return builder.build();
    }

    /** A fresh random 128-bit W3C trace ID (32 lowercase hex). */
    protected static String randomTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** {@code traceparent} for {@code traceId}, sampled, with a random parent span id. */
    protected static String traceparent(String traceId) {
        String spanId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    protected static HttpClient http() {
        return HTTP;
    }
}
