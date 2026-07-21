package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.FlywayPresenceCheck;
import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.AuditorAware;

/**
 * The mandatory ContextRunner matrix for PlatformDataJpaAutoConfiguration. Runs without JPA
 * infrastructure: the auditing enablement backs off (no EntityManagerFactory), so the wiring of the
 * auditor, capability descriptor, and Flyway guard is exercised in isolation. End-to-end auditing
 * against a real EntityManagerFactory is covered by the H2 {@code @DataJpaTest} slice.
 */
class PlatformDataJpaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformDataJpaAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
            assertThat(context).hasSingleBean(FlywayPresenceCheck.class);
            assertThat(context).hasBean("platformAuditorAware");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.data.jpa.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                    assertThat(context).doesNotHaveBean(FlywayPresenceCheck.class);
                    assertThat(context).doesNotHaveBean(AuditorAware.class);
                });
    }

    @Test
    void backsOffWhenUserAuditorPresent() {
        AuditorAware<String> mine = () -> Optional.of("tester");
        runner.withBean("platformAuditorAware", AuditorAware.class, () -> mine)
                .run(context -> assertThat(context.getBean(AuditorAware.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(EntityManagerFactory.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformDataJpaAutoConfiguration.class));
    }

    @Test
    void requireMigrationsBindsFromKebabKey() {
        runner.withPropertyValues("dc.platform.data.jpa.require-migrations=false")
                .run(context -> assertThat(context.getBean(DataJpaProperties.class).requireMigrations()).isFalse());
    }

    @Test
    void auditorDefaultsToSystemWithoutAuthenticatedUser() {
        runner.run(context -> {
            @SuppressWarnings("unchecked")
            AuditorAware<String> auditor = context.getBean(AuditorAware.class);
            assertThat(auditor.getCurrentAuditor()).contains("system");
        });
    }
}
