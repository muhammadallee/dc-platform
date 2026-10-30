package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** The data source Boot builds for {@code spring.flyway.user}/{@code url}; nothing connects here. */
class OracleFlywayConnectionDefaultsTest {

    private final OracleFlywayConnectionDefaults defaults = new OracleFlywayConnectionDefaults(new MockEnvironment());

    @Test
    void aSeparateOracleMigrationDataSourceGetsTheLongerFlywayReadTimeoutAndTheConnectTimeout() {
        // 10 min, not the pool's 60 s: one migration statement (an index build) may run for minutes.
        SimpleDriverDataSource dataSource = dataSource("jdbc:oracle:thin:@//db:1521/APP");
        defaults.postProcessBeforeInitialization(flyway(dataSource), "flyway");

        assertThat(dataSource.getConnectionProperties())
                .containsEntry("oracle.jdbc.ReadTimeout", "600000")
                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
    }

    @Test
    void theFlywayReadTimeoutPropertySetsTheMigrationBound() {
        OracleFlywayConnectionDefaults configured = new OracleFlywayConnectionDefaults(new MockEnvironment()
                .withProperty("dc.platform.data.jpa.oracle.flyway-read-timeout", "30m")
                .withProperty("dc.platform.data.jpa.oracle.read-timeout", "2m"));
        SimpleDriverDataSource dataSource = dataSource("jdbc:oracle:thin:@//db:1521/APP");
        configured.postProcessBeforeInitialization(flyway(dataSource), "flyway");

        assertThat(dataSource.getConnectionProperties()).containsEntry("oracle.jdbc.ReadTimeout", "1800000");
    }

    @Test
    void anotherDatabaseIsLeftAlone() {
        SimpleDriverDataSource dataSource = dataSource("jdbc:h2:mem:app");
        defaults.postProcessBeforeInitialization(flyway(dataSource), "flyway");

        assertThat(dataSource.getConnectionProperties()).isNull();
    }

    @Test
    void existingConnectionPropertiesAndJvmSystemPropertiesWin() {
        SimpleDriverDataSource dataSource = dataSource("jdbc:oracle:thin:@//db:1521/APP");
        Properties existing = new Properties();
        existing.setProperty("oracle.jdbc.ReadTimeout", "120000");
        dataSource.setConnectionProperties(existing);
        System.setProperty("oracle.net.CONNECT_TIMEOUT", "3000");
        try {
            defaults.postProcessBeforeInitialization(flyway(dataSource), "flyway");
        } finally {
            System.clearProperty("oracle.net.CONNECT_TIMEOUT");
        }

        assertThat(dataSource.getConnectionProperties())
                .containsEntry("oracle.jdbc.ReadTimeout", "120000")
                .doesNotContainKey("oracle.net.CONNECT_TIMEOUT");
    }

    @Test
    void otherBeansPassThrough() {
        Object bean = new Object();
        assertThat(defaults.postProcessBeforeInitialization(bean, "other")).isSameAs(bean);
    }

    private static SimpleDriverDataSource dataSource(String url) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static Flyway flyway(SimpleDriverDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).load();
    }
}
