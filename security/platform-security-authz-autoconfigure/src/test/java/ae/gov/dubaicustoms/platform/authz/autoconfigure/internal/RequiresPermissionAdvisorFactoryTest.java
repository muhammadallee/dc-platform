package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.authz.RequiresPermission;
import ae.gov.dubaicustoms.platform.authz.spi.PermissionEvaluatorProvider;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class RequiresPermissionAdvisorFactoryTest {

    interface Orders {
        @RequiresPermission("orders:read")
        String get();
    }

    static class OrdersImpl implements Orders {
        @Override
        public String get() {
            return "orders";
        }
    }

    private Orders proxy(CurrentUserAccessor accessor, PermissionEvaluatorProvider... providers) {
        Advisor advisor = RequiresPermissionAdvisorFactory.create(accessor, List.of(providers));
        ProxyFactory factory = new ProxyFactory(new OrdersImpl());
        factory.addAdvisor(advisor);
        return (Orders) factory.getProxy();
    }

    @Test
    void allowsWhenAProviderGrants() {
        CurrentUserAccessor accessor = () -> Optional.of(new CurrentUser("alice", null, Set.of(), Map.of()));
        Orders orders = proxy(accessor, (u, p) -> true);

        assertThat(orders.get()).isEqualTo("orders");
    }

    @Test
    void deniesWhenNoProviderGrants() {
        CurrentUserAccessor accessor = () -> Optional.of(new CurrentUser("alice", null, Set.of(), Map.of()));
        Orders orders = proxy(accessor, (u, p) -> false);

        assertThatThrownBy(orders::get).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requiresAuthenticationBeforeCheckingPermission() {
        CurrentUserAccessor accessor = Optional::empty;
        Orders orders = proxy(accessor, (u, p) -> true);

        assertThatThrownBy(orders::get).isInstanceOf(InsufficientAuthenticationException.class);
    }
}
