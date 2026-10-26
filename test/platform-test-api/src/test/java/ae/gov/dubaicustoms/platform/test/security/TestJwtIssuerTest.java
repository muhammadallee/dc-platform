package ae.gov.dubaicustoms.platform.test.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Verifies the issuer against the same Nimbus decoder a Boot resource server builds from a jwk-set-uri. */
class TestJwtIssuerTest {

    private final TestJwtIssuer issuer = TestJwtIssuer.start();
    private final NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer.jwkSetUri()).build();

    @AfterEach
    void stop() {
        issuer.close();
    }

    @Test
    void servesALoopbackJwkSet() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(issuer.jwkSetUri())).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(issuer.jwkSetUri()).startsWith("http://127.0.0.1:").endsWith("/.well-known/jwks.json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.body()).contains("\"kty\":\"RSA\"").contains("\"alg\":\"RS256\"");
    }

    @Test
    void jwkSetRejectsNonGetMethods() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(issuer.jwkSetUri())).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void mintsTokensTheResourceServerDecoderAccepts() {
        Jwt jwt = decoder.decode(issuer.token("alice"));

        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(jwt.getClaimAsString("preferred_username")).isEqualTo("alice");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(issuer.issuerUri());
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void carriesCustomClaimsOfEverySupportedType() {
        Jwt jwt = decoder.decode(issuer.token("bob",
                Map.of("tenant", "dxb \"quoted\"\n\\", "level", 3, "admin", true, "roles", List.of("USER", "AUDITOR")),
                Duration.ofMinutes(1)));

        assertThat(jwt.getClaimAsString("tenant")).isEqualTo("dxb \"quoted\"\n\\");
        assertThat(((Number) jwt.getClaim("level")).intValue()).isEqualTo(3);
        assertThat(jwt.<Boolean>getClaim("admin")).isTrue();
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER", "AUDITOR");
    }

    @Test
    void escapesControlCharactersInClaims() {
        Jwt jwt = decoder.decode(issuer.token("carol", Map.of("note", "tab\tcr\rbell"), Duration.ofMinutes(1)));

        assertThat(jwt.getClaimAsString("note")).isEqualTo("tab\tcr\rbell");
    }

    @Test
    void rejectsUnsupportedClaimTypes() {
        assertThatThrownBy(() -> issuer.token("dave", Map.of("when", new Object()), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported claim value type");
    }

    @Test
    void expiredTokensFailValidation() {
        assertThatThrownBy(() -> decoder.decode(issuer.expiredToken("alice")))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void untrustedTokensFailSignatureVerification() {
        assertThatThrownBy(() -> decoder.decode(issuer.untrustedToken("mallory")))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> issuer.token(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.token("x", null, Duration.ZERO)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.token("x", Map.of(), null)).isInstanceOf(NullPointerException.class);
    }
}
