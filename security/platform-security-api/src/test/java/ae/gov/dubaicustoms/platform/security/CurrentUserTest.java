package ae.gov.dubaicustoms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CurrentUserTest {

    @Test
    void tenantMayBeNull() {
        CurrentUser user = new CurrentUser("alice", null, Set.of("admin"), Map.of());

        assertThat(user.tenant()).isNull();
        assertThat(user.subject()).isEqualTo("alice");
    }

    @Test
    void rolesAndClaimsAreDefensivelyCopiedAndImmutable() {
        Set<String> roles = new HashSet<>(Set.of("admin"));
        Map<String, Object> claims = new HashMap<>(Map.of("iss", "issuer"));

        CurrentUser user = new CurrentUser("alice", "tenant-1", roles, claims);
        roles.add("mutated-after-construction");
        claims.put("mutated", true);

        assertThat(user.roles()).containsExactly("admin");
        assertThat(user.claims()).containsOnly(Map.entry("iss", "issuer"));
        assertThatThrownBy(() -> user.roles().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> user.claims().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullSubjectRolesOrClaims() {
        assertThatNullPointerException().isThrownBy(() -> new CurrentUser(null, "t", Set.of(), Map.of()));
        assertThatNullPointerException().isThrownBy(() -> new CurrentUser("alice", "t", null, Map.of()));
        assertThatNullPointerException().isThrownBy(() -> new CurrentUser("alice", "t", Set.of(), null));
    }
}
