package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

class OAuth2TokenRelayCustomizerTest {

    private final MockWebServer server = new MockWebServer();

    @BeforeEach
    void startServer() throws IOException {
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void relaysTheBearerTokenFromTheCurrentJwtPrincipal() throws Exception {
        server.enqueue(new MockResponse());
        Jwt jwt = Jwt.withTokenValue("the-token")
                .header("alg", "none")
                .subject("alice")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null));

        RestClient.Builder builder = RestClient.builder().baseUrl(server.url("/").toString());
        new OAuth2TokenRelayCustomizer().customize("orders", builder);
        builder.build().get().uri("/widgets").retrieve().toBodilessEntity();

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer the-token");
    }

    @Test
    void doesNothingWhenNoJwtPrincipalPresent() throws Exception {
        server.enqueue(new MockResponse());

        RestClient.Builder builder = RestClient.builder().baseUrl(server.url("/").toString());
        new OAuth2TokenRelayCustomizer().customize("orders", builder);
        builder.build().get().uri("/widgets").retrieve().toBodilessEntity();

        assertThat(server.takeRequest().getHeader("Authorization")).isNull();
    }
}
