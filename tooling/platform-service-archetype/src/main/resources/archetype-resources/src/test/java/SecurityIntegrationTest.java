package ${package};

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Real-HTTP security contract: business endpoints reject missing, untrusted, and expired bearer tokens
 * with an RFC-9457 body, accept a token the configured JWK Set vouches for, and the probe endpoints stay
 * open. Unlike {@code HelloControllerTest}, nothing here bypasses the JWT decoder.
 */
class SecurityIntegrationTest extends HttpIntegrationTestSupport {

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        HttpResponse<String> response = get("/hello", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
    }

    @Test
    void rejectsTokenSignedByAnUntrustedKey() throws Exception {
        assertThat(get("/hello", ISSUER.untrustedToken("mallory"), null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        assertThat(get("/hello", ISSUER.expiredToken("alice"), null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsMalformedToken() throws Exception {
        assertThat(get("/hello", "not-a-jwt", null).statusCode()).isEqualTo(401);
    }

    @Test
    void acceptsTokenFromTheConfiguredIssuer() throws Exception {
        HttpResponse<String> response = get("/hello?name=alice", ISSUER.token("alice"), null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello, alice!");
    }

    @Test
    void healthAndReadinessProbesAreOpenAndUp() throws Exception {
        HttpResponse<String> health = get("/actuator/health", null, null);
        HttpResponse<String> readiness = get("/actuator/health/readiness", null, null);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
    }
}
