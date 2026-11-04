package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OracleConnectionDefaultsTest {

    private final OracleConnectionDefaults defaults = new OracleConnectionDefaults(new MockEnvironment());

    @Test
    void anOraclePoolGetsTheDriverReadAndConnectTimeouts() {
        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
        defaults.postProcessBeforeInitialization(pool, "dataSource");

        assertThat(pool.getDataSourceProperties())
                .containsEntry("oracle.jdbc.ReadTimeout", "60000")
                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
    }

    @Test
    void thePlatformReadTimeoutPropertySetsThePoolBound() {
        // A service whose transactions run longer than 60 s raises the per-statement cap with it.
        OracleConnectionDefaults configured = new OracleConnectionDefaults(
                new MockEnvironment().withProperty("dc.platform.data.jpa.oracle.read-timeout", "5m"));
        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
        configured.postProcessBeforeInitialization(pool, "dataSource");

        assertThat(pool.getDataSourceProperties())
                .containsEntry("oracle.jdbc.ReadTimeout", "300000")
                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
    }

    @Test
    void anotherDatabaseIsLeftAlone() {
        // An Oracle-starter service runs on H2 locally with ojdbc11 on the classpath.
        HikariDataSource pool = pool("jdbc:h2:mem:app");
        defaults.postProcessBeforeInitialization(pool, "dataSource");

        assertThat(pool.getDataSourceProperties()).isEmpty();
    }

    @Test
    void anApplicationValueWins() {
        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
        pool.addDataSourceProperty("oracle.jdbc.ReadTimeout", "120000");
        defaults.postProcessBeforeInitialization(pool, "dataSource");

        assertThat(pool.getDataSourceProperties())
                .containsEntry("oracle.jdbc.ReadTimeout", "120000")
                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
    }

    @Test
    void aJvmSystemPropertyWins() {
        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
        System.setProperty("oracle.net.CONNECT_TIMEOUT", "3000");
        try {
            defaults.postProcessBeforeInitialization(pool, "dataSource");
        } finally {
            System.clearProperty("oracle.net.CONNECT_TIMEOUT");
        }

        assertThat(pool.getDataSourceProperties()).doesNotContainKey("oracle.net.CONNECT_TIMEOUT");
    }

    @Test
    void aPoolTheApplicationAlreadyStartedIsLeftAlone() {
        // new HikariDataSource(config) starts the pool and seals its configuration; adding to it throws.
        try (HikariDataSource pool = startedPool()) {
            assertThat(defaults.postProcessBeforeInitialization(pool, "dataSource")).isSameAs(pool);

            assertThat(pool.getDataSourceProperties())
                    .containsOnlyKeys("oracle.jdbc.ReadTimeout")
                    .containsEntry("oracle.jdbc.ReadTimeout", "120000");
        }
    }

    @Test
    void aStartedPoolThatIsSuspendedIsLeftAloneToo() {
        // A suspended pool is not "running" but its configuration is still sealed.
        try (HikariDataSource pool = startedPool(true)) {
            pool.getHikariPoolMXBean().suspendPool();
            assertThat(pool.isRunning()).isFalse();

            defaults.postProcessBeforeInitialization(pool, "dataSource");

            assertThat(pool.getDataSourceProperties()).containsOnlyKeys("oracle.jdbc.ReadTimeout");
        }
    }

    @Test
    void otherBeansPassThrough() {
        Object bean = new Object();
        assertThat(defaults.postProcessBeforeInitialization(bean, "other")).isSameAs(bean);
    }

    private static HikariDataSource pool(String url) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        return pool;
    }

    /** An Oracle pool started by its constructor without connecting: no idle connections, no fail-fast. */
    private static HikariDataSource startedPool() {
        return startedPool(false);
    }

    private static HikariDataSource startedPool(boolean suspendable) {
        HikariConfig config = new HikariConfig();
        config.setAllowPoolSuspension(suspendable);
        config.setJdbcUrl("jdbc:oracle:thin:@//127.0.0.1:9/APP");
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("oracle.jdbc.ReadTimeout", "120000");
        return new HikariDataSource(config);
    }
}
