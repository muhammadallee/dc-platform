package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaEnvironmentPostProcessor;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OutOfOrderMigrationsException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import oracle.jdbc.OracleDriver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Flyway posture on Oracle (scope R5, R6.1, R7, AC7, D110, D112) under the {@code prod} profile with the
 * platform defaults applied as at startup, each case in its own fresh schema: out-of-order, a misnamed
 * migration, an edited applied migration and an over-long identifier all block startup with a clear
 * message; {@code clean} is refused; and a separate migration user (owner) gets the Oracle network bounds
 * on Flyway's own data source. Excluded from the default build (docker JUnit tag); run under
 * {@code -Pdocker}.
 */
@Tag("docker")
class OracleFlywayPostureIT {

    private static ApplicationContextRunner runner(String[] user, String location) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().setActiveProfiles("prod");
                    new PlatformDataJpaEnvironmentPostProcessor()
                            .postProcessEnvironment(context.getEnvironment(), new SpringApplication());
                })
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, PlatformDataJpaAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + OracleContainers.oracle().getJdbcUrl(),
                        "spring.datasource.username=" + user[0], "spring.datasource.password=" + user[1],
                        "spring.flyway.locations=classpath:db/" + location);
    }

    @Test
    void outOfOrderUnderProdFailsStartup() {
        runner(OracleContainers.freshUser(), "flyway-posture-oracle-ok")
                .withPropertyValues("spring.flyway.out-of-order=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(OutOfOrderMigrationsException.class));
    }

    @Test
    void aMisnamedMigrationBlocksStartup() {
        runner(OracleContainers.freshUser(), "flyway-posture-oracle-badname").run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("V1_missing_double_underscore"));
    }

    @Test
    void anEditedAppliedMigrationBlocksStartup() {
        String[] user = OracleContainers.freshUser();
        runner(user, "flyway-posture-oracle-checksum-a").run(context -> assertThat(context).hasNotFailed());
        runner(user, "flyway-posture-oracle-checksum-b").run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("checksum mismatch"));
    }

    @Test
    void anIdentifierOverTheLimitFailsAtMigrationTimeNamingTheMigration() {
        runner(OracleContainers.freshUser(), "flyway-posture-oracle-long-identifier").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertThat(failure).isNotNull();
            assertThat(failure).hasStackTraceContaining("V1__long_identifier.sql")
                    .hasStackTraceContaining("ORA-00972");
        });
    }

    @Test
    void cleanIsRefused() {
        runner(OracleContainers.freshUser(), "flyway-posture-oracle-ok").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(context.getBean(Flyway.class)::clean).hasMessageContaining("clean");
        });
    }

    @Test
    void aJvmSystemPropertyBoundsADriverDataSourceConnection() {
        // The documented workaround for a {vendor} location: the driver reads oracle.jdbc.ReadTimeout from
        // the JVM's system properties for a connection that carries no such property of its own.
        String[] user = OracleContainers.freshUser();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new OracleDriver(),
                OracleContainers.oracle().getJdbcUrl(), user[0], user[1]);
        System.setProperty("oracle.jdbc.ReadTimeout", "1000");
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> new JdbcTemplate(dataSource).execute("begin dbms_session.sleep(5); end;"))
                    .hasStackTraceContaining("timed out");
        } finally {
            System.clearProperty("oracle.jdbc.ReadTimeout");
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(4_000L);
    }

    @Test
    void aSeparateMigrationUserGetsTheOracleNetworkBoundsAndMigratesItsOwnSchema() throws SQLException {
        String[] runtime = OracleContainers.freshUser();
        String[] owner = OracleContainers.freshUser();
        runner(runtime, "flyway-posture-oracle-ok")
                .withPropertyValues("spring.flyway.user=" + owner[0], "spring.flyway.password=" + owner[1])
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AbstractDriverBasedDataSource migration = (AbstractDriverBasedDataSource) context
                            .getBean(Flyway.class).getConfiguration().getDataSource();
                    assertThat(migration.getUsername()).isEqualTo(owner[0]);
                    assertThat(migration.getConnectionProperties())
                            .containsEntry("oracle.jdbc.ReadTimeout", "600000") // flyway-read-timeout, 10 min
                            .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
                });
        try (Connection connection = DriverManager.getConnection(OracleContainers.oracle().getJdbcUrl(), owner[0],
                owner[1]); ResultSet tables = connection.createStatement()
                .executeQuery("select count(*) from user_tables where table_name = 'POSTURE_OK'")) {
            tables.next();
            assertThat(tables.getInt(1)).as("Flyway migrated the owner's schema").isEqualTo(1);
        }
    }
}
