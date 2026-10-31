package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OutOfOrderMigrationsException;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OutOfOrderMigrationsFailureAnalyzer;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

/**
 * Flyway posture (scope R5, D112, AC7) on H2 with the platform defaults applied as at startup:
 * out-of-order refused outside {@code local} with an actionable diagnostic, a misnamed migration and
 * an edited applied migration both block startup, and {@code clean} is refused.
 */
class FlywayPostureTest {

    private static ApplicationContextRunner runner(String database, String location) {
        return new ApplicationContextRunner()
                .withInitializer(context -> new PlatformDataJpaEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, PlatformDataJpaAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1",
                        "spring.flyway.locations=classpath:db/" + location);
    }

    private static String database() {
        return "posture-" + UUID.randomUUID();
    }

    @Test
    void outOfOrderOutsideTheLocalProfileFailsStartupWithAnActionableDiagnostic() {
        runner(database(), "flyway-posture-ok").withPropertyValues("spring.flyway.out-of-order=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    FailureAnalysis analysis = new OutOfOrderMigrationsFailureAnalyzer().analyze(failure);
                    assertThat(analysis).isNotNull();
                    assertThat(analysis.getDescription()).contains("out-of-order").contains("'local'");
                    assertThat(analysis.getAction()).contains("spring.flyway.out-of-order");
                    assertThat(failure).hasRootCauseInstanceOf(OutOfOrderMigrationsException.class);
                });
    }

    @Test
    void aCustomizerThatEnablesOutOfOrderIsCaughtWhateverItsOrder() {
        FlywayConfigurationCustomizer unordered = configuration -> configuration.outOfOrder(true);
        runner(database(), "flyway-posture-ok")
                .withBean("unorderedOutOfOrder", FlywayConfigurationCustomizer.class, () -> unordered)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(OutOfOrderMigrationsException.class));
        runner(database(), "flyway-posture-ok")
                .withBean("lastOutOfOrder", LastCustomizer.class, LastCustomizer::new)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(OutOfOrderMigrationsException.class));
    }

    @Test
    void theGuardCanBeReplacedByName() {
        runner(database(), "flyway-posture-ok").withPropertyValues("spring.flyway.out-of-order=true")
                .withBean("platformFlywayOutOfOrderGuard", Object.class, Object::new)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theKillSwitchRemovesTheFlywayChecks() {
        runner(database(), "flyway-posture-ok")
                .withPropertyValues("spring.flyway.out-of-order=true", "dc.platform.data.jpa.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("platformFlywayOutOfOrderGuard")
                            .doesNotHaveBean("platformOracleFlywayConnectionDefaults");
                });
    }

    /** Enables out-of-order with the lowest precedence, i.e. competes with the guard for "last". */
    static final class LastCustomizer implements FlywayConfigurationCustomizer, Ordered {

        @Override
        public void customize(FluentConfiguration configuration) {
            configuration.outOfOrder(true);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    @Test
    void outOfOrderIsAllowedUnderTheLocalProfile() {
        runner(database(), "flyway-posture-ok")
                .withPropertyValues("spring.flyway.out-of-order=true", "spring.profiles.active=local")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aMisnamedMigrationBlocksStartup() {
        runner(database(), "flyway-posture-badname").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("V1_missing_double_underscore");
        });
    }

    @Test
    void anEditedAppliedMigrationBlocksStartup() {
        String database = database();
        runner(database, "flyway-posture-checksum-a").run(context -> assertThat(context).hasNotFailed());
        runner(database, "flyway-posture-checksum-b").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("checksum mismatch");
        });
    }

    @Test
    void cleanIsRefused() {
        runner(database(), "flyway-posture-ok").run(context -> {
            assertThat(context).hasNotFailed();
            Flyway flyway = context.getBean(Flyway.class);
            assertThatThrownBy(flyway::clean).hasMessageContaining("clean");
            assertThat(((ConfigurableApplicationContext) context.getSourceApplicationContext()).isActive()).isTrue();
        });
    }
}
