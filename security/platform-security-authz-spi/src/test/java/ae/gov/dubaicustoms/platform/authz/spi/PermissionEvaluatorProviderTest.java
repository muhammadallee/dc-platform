package ae.gov.dubaicustoms.platform.authz.spi;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.security.CurrentUser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionEvaluatorProviderTest {

    @Test
    void invokesTheLambdaWithUserAndPermission() {
        CurrentUser user = new CurrentUser("alice", null, Set.of("admin"), Map.of());
        PermissionEvaluatorProvider provider = (u, permission) -> u.roles().contains(permission);

        assertThat(provider.hasPermission(user, "admin")).isTrue();
        assertThat(provider.hasPermission(user, "orders:read")).isFalse();
    }
}
