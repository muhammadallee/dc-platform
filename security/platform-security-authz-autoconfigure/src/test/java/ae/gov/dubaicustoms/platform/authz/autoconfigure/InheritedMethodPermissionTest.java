package ae.gov.dubaicustoms.platform.authz.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.authz.RequiresPermission;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.access.AccessDeniedException;

/** A class-level permission applies to a method the class inherits from an unannotated parent. */
class InheritedMethodPermissionTest {

    public static class BaseOrders {
        public String list() {
            return "orders";
        }
    }

    @RequiresPermission("orders:read")
    public static class Orders extends BaseOrders {
    }

    private static ApplicationContextRunner runner(Set<String> roles) {
        CurrentUserAccessor accessor = () -> Optional.of(new CurrentUser("alice", null, roles, Map.of()));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformAuthzAutoConfiguration.class))
                .withBean(CurrentUserAccessor.class, () -> accessor)
                .withBean(Orders.class);
    }

    @Test
    void anAuthorizedCallerReachesTheInheritedMethod() {
        runner(Set.of("ROLE_orders:read")).run(context -> assertThat(context.getBean(Orders.class).list()).isEqualTo("orders"));
    }

    @Test
    void anUnauthorizedCallerIsDeniedNotAFailure() {
        runner(Set.of()).run(context -> assertThatThrownBy(() -> context.getBean(Orders.class).list())
                .isInstanceOf(AccessDeniedException.class));
    }
}
