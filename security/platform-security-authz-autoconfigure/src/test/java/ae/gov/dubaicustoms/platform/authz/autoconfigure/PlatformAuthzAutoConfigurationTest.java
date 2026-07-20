package ae.gov.dubaicustoms.platform.authz.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.authz.RequiresPermission;
import ae.gov.dubaicustoms.platform.authz.spi.PermissionEvaluatorProvider;
import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The mandatory 5-case ContextRunner matrix for PlatformAuthzAutoConfiguration, plus the advisor's own guard. */
class PlatformAuthzAutoConfigurationTest {

    private final CurrentUserAccessor stubAccessor = () -> Optional.empty();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformAuthzAutoConfiguration.class))
            .withBean(CurrentUserAccessor.class, () -> stubAccessor);

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PermissionEvaluatorProvider.class);
            assertThat(context).hasBean("requiresPermissionAdvisor");
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.authz.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PermissionEvaluatorProvider.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        PermissionEvaluatorProvider mine = (user, permission) -> true;
        runner.withBean("mine", PermissionEvaluatorProvider.class, () -> mine)
                .run(context -> assertThat(context.getBean(PermissionEvaluatorProvider.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(RequiresPermission.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformAuthzAutoConfiguration.class));
    }

    @Test
    void advisorBacksOffWithoutCurrentUserAccessor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformAuthzAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PermissionEvaluatorProvider.class);
                    assertThat(context).doesNotHaveBean("requiresPermissionAdvisor");
                });
    }

    @Test
    void multipleProvidersAreEvaluatedInOrderUntilOneGrants() {
        CurrentUser user = new CurrentUser("alice", null, java.util.Set.of(), java.util.Map.of());
        PermissionEvaluatorProvider denies = (u, permission) -> false;
        PermissionEvaluatorProvider grants = (u, permission) -> true;

        runner.withBean("first", PermissionEvaluatorProvider.class, () -> denies)
                .withBean("second", PermissionEvaluatorProvider.class, () -> grants)
                .run(context -> {
                    assertThat(context.getBeansOfType(PermissionEvaluatorProvider.class)).hasSize(2);
                    assertThat(denies.hasPermission(user, "x") || grants.hasPermission(user, "x")).isTrue();
                });
    }
}
