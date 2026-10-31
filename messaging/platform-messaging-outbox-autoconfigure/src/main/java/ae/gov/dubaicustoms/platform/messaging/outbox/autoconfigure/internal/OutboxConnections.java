package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The drainer's own bounded connections, so a stuck or backlogged drainer can never starve request threads. Holds the
 * pool instead of being a {@code DataSource} bean: such a bean would make Boot back off from the service's own pool.
 *
 * <p>A Hikari application pool is copied, so the drainer gets the same URL, credentials, driver properties (the Oracle
 * read and connect bounds, added to the application's pool before this runs) and connection-init SQL. Any other data
 * source is wrapped. The copy does not connect until first use. Thread-safe.
 */
public final class OutboxConnections implements AutoCloseable {

    private static final String POOL_NAME = "platform-outbox";

    private final HikariDataSource pool;

    private OutboxConnections(HikariDataSource pool) {
        this.pool = pool;
    }

    /**
     * Builds the drainer's pool.
     *
     * @param application the application's data source; never {@code null}
     * @param poolSize the most connections the drainer may hold
     * @param registry receives the pool's meters, or {@code null} for none
     * @return the connections; never {@code null}
     */
    public static OutboxConnections of(DataSource application, int poolSize, MeterRegistry registry) {
        HikariConfig config = new HikariConfig();
        HikariDataSource hikari = unwrapHikari(application);
        if (hikari != null) {
            hikari.copyStateTo(config);
        } else {
            config.setDataSource(application);
        }
        config.setPoolName(POOL_NAME);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        // Never fails startup on its own: Flyway and the drainer's table check report a missing database clearly.
        config.setInitializationFailTimeout(-1);
        config.setMetricRegistry(null);
        config.setHealthCheckRegistry(null);
        config.setMetricsTrackerFactory(registry != null ? new MicrometerMetricsTrackerFactory(registry) : null);
        return new OutboxConnections(new HikariDataSource(config));
    }

    private static HikariDataSource unwrapHikari(DataSource dataSource) {
        try {
            return dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * The pool. Not exposed as a bean.
     *
     * @return the drainer's data source; never {@code null}
     */
    public DataSource dataSource() {
        return pool;
    }

    @Override
    public void close() {
        pool.close();
    }
}
