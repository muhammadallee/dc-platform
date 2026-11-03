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

    interface Ledger {
        String delete();

        String audit();
    }

    @RequiresPermission("ledger:read")
    static class LedgerImpl implements Ledger {
        @Override
        @RequiresPermission("ledger:delete")
        public String delete() {
            return "deleted";
        }

        @Override
        public String audit() {
            return "audited";
        }
    }

    interface Archive {
        String purge();
    }

    static class ArchiveImpl implements Archive {
        @Override
        @RequiresPermission("archive:purge")
        public String purge() {
            return "purged";
        }
    }

    private static <T> T jdkProxy(Object target, Class<T> type, String... granted) {
        CurrentUserAccessor accessor = () -> Optional.of(new CurrentUser("alice", null, Set.of(), Map.of()));
        PermissionEvaluatorProvider provider = (u, p) -> Set.of(granted).contains(p);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(type);
        factory.addAdvisor(RequiresPermissionAdvisorFactory.create(accessor, List.of(provider)));
        return type.cast(factory.getProxy());
    }

    @Test
    void throughAnInterfaceTheImplementingMethodPermissionWinsOverTheClassPermission() {
        Ledger ledger = jdkProxy(new LedgerImpl(), Ledger.class, "ledger:read");

        assertThatThrownBy(ledger::delete).isInstanceOf(AccessDeniedException.class).hasMessageContaining("ledger:delete");
        assertThat(ledger.audit()).isEqualTo("audited");
        assertThat(jdkProxy(new LedgerImpl(), Ledger.class, "ledger:read", "ledger:delete").delete()).isEqualTo("deleted");
    }

    @Test
    void throughAnInterfaceAPermissionOnlyOnTheImplementingMethodIsEnforced() {
        assertThatThrownBy(jdkProxy(new ArchiveImpl(), Archive.class)::purge).isInstanceOf(AccessDeniedException.class);
        assertThat(jdkProxy(new ArchiveImpl(), Archive.class, "archive:purge").purge()).isEqualTo("purged");
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
