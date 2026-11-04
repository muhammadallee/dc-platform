package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RolesClaimPermissionProviderRolesTest {

    private final RolesClaimPermissionProvider provider = new RolesClaimPermissionProvider("roles");

    @Test
    void aMappedRoleGrantsThePermissionEvenWhenTheClaimListsOthers() {
        CurrentUser user = new CurrentUser("alice", null, Set.of("ROLE_orders:read"), Map.of("roles", List.of("other")));
        assertThat(provider.hasPermission(user, "orders:read")).isTrue();
    }

    @Test
    void theClaimStillGrants() {
        CurrentUser user = new CurrentUser("alice", null, Set.of(), Map.of("roles", List.of("orders:read")));
        assertThat(provider.hasPermission(user, "orders:read")).isTrue();
        assertThat(provider.hasPermission(new CurrentUser("alice", null, Set.of(), Map.of("roles", "orders:read")), "orders:read")).isTrue();
    }

    @Test
    void aScopeIsNotAPermission() {
        CurrentUser user = new CurrentUser("alice", null, Set.of("SCOPE_orders:read"), Map.of());
        assertThat(provider.hasPermission(user, "orders:read")).isFalse();
    }

    @Test
    void aClaimOfTheWrongShapeGrantsNothing() {
        CurrentUser user = new CurrentUser("alice", null, Set.of(), Map.of("roles", Map.of("orders:read", true)));
        assertThat(provider.hasPermission(user, "orders:read")).isFalse();
        assertThat(provider.hasPermission(new CurrentUser("a", null, Set.of(), Map.of("roles", List.of(7))), "7")).isFalse();
    }
}
