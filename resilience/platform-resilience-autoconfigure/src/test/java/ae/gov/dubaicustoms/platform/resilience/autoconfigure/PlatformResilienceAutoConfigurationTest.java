package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.resilience.RetryableOperation;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The mandatory ContextRunner matrix for PlatformResilienceAutoConfiguration. A RetryRegistry (which
 * resilience4j-spring-boot4 contributes at runtime) is supplied directly here so the wiring of the
 * RetryableOperation over it is exercised without pulling in the whole Resilience4j Spring Boot
 * integration.
 */
class PlatformResilienceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformResilienceAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.withBean(RetryRegistry.class, RetryRegistry::ofDefaults)
                .run(context -> {
                    assertThat(context).hasSingleBean(RetryableOperation.class);
                    assertThat(context).hasSingleBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void killSwitchDisables() {
        runner.withBean(RetryRegistry.class, RetryRegistry::ofDefaults)
                .withPropertyValues("dc.platform.resilience.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RetryableOperation.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        RetryableOperation mine = new RetryableOperation() {
            @Override
            public <T> T call(String name, java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
        runner.withBean(RetryRegistry.class, RetryRegistry::ofDefaults)
                .withBean("mine", RetryableOperation.class, () -> mine)
                .run(context -> assertThat(context.getBean(RetryableOperation.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(RetryRegistry.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformResilienceAutoConfiguration.class));
    }

    @Test
    void retryableOperationRequiresARetryRegistry() {
        // Without resilience4j-spring-boot4 (no RetryRegistry bean) the helper is not wired, but the
        // capability is still reported active so its declarative annotations remain usable.
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RetryableOperation.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }
}
