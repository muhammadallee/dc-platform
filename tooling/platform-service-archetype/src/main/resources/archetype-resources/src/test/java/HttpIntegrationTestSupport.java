package ${package};

import ae.gov.dubaicustoms.platform.test.security.TestJwtIssuer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that drive the service over REAL HTTP on a random port, with bearer tokens validated
 * against a loopback JWK Set ({@link TestJwtIssuer}) instead of the {@code MockMvc} JWT shortcut. Every
 * subclass shares one application context and one issuer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class HttpIntegrationTestSupport {

    /** Shared for the JVM: cached contexts keep pointing at it, so it is never stopped early. */
    protected static final TestJwtIssuer ISSUER = TestJwtIssuer.start();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void jwtIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
    }

    /** GET {@code path}; {@code bearer} and {@code correlationId} are optional (null = header omitted). */
    protected HttpResponse<String> get(String path, String bearer, String correlationId)
            throws IOException, InterruptedException {
        return HTTP.send(request(path, bearer, correlationId), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpRequest request(String path, String bearer, String correlationId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-Id", correlationId);
        }
        return builder.build();
    }

    protected static HttpClient http() {
        return HTTP;
    }
}
