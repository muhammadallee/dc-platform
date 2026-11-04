package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class PlatformJwtAuthoritiesConverterTest {

    private final PlatformJwtAuthoritiesConverter converter = new PlatformJwtAuthoritiesConverter("orders");

    @Test
    void mapsScopesAndRolesOfAKeycloakToken() {
        assertThat(authorities(Map.of(
                "scope", "read write",
                "realm_access", Map.of("roles", List.of("clerk")),
                "resource_access", Map.of("orders", Map.of("roles", List.of("approver")))
        ))).containsExactlyInAnyOrder("SCOPE_read", "SCOPE_write", "ROLE_clerk", "ROLE_approver");
    }

    @Test
    void readsAPlainRolesClaim() {
        assertThat(authorities(Map.of("roles", List.of("a", "b")))).containsExactlyInAnyOrder("ROLE_a", "ROLE_b");
        assertThat(authorities(Map.of("roles", "a"))).containsExactly("ROLE_a");
    }

    @Test
    void anotherClientsRolesGrantNothing() {
        assertThat(authorities(Map.of("resource_access", Map.of("billing", Map.of("roles", List.of("admin")))))).isEmpty();
    }

    @Test
    void noClientConfiguredReadsNoClientRoles() {
        Jwt jwt = jwt(Map.of("resource_access", Map.of("orders", Map.of("roles", List.of("admin")))));
        assertThat(new PlatformJwtAuthoritiesConverter(null).convert(jwt)).isEmpty();
    }

    @Test
    void claimsOfTheWrongShapeGrantNothing() {
        assertThat(authorities(Map.of(
                "roles", 7,
                "realm_access", "clerk",
                "resource_access", Map.of("orders", List.of("admin"))
        ))).isEmpty();
        assertThat(authorities(Map.of("realm_access", Map.of("roles", List.of(1, "", Map.of("x", "y")))))).isEmpty();
    }

    private List<String> authorities(Map<String, Object> claims) {
        return converter.convert(jwt(claims)).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("alice").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claims(all -> all.putAll(claims)).build();
    }
}
