package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/**
 * The drainer's own bounded connections, so a stuck or backlogged drainer can never starve request threads. Holds the
 * pool instead of being a {@code DataSource} bean: such a bean would make Boot back off from the service's own pool.
 *
 * <p>Built from an allow-list of a Hikari application pool's connection settings (URL, credentials, driver properties,
 * connection-init SQL), never its pool behaviour: not its size, auto-commit, metrics or timeouts. On Oracle it adds the
 * driver's read and connect bounds itself, because a service without the data-jpa starter or with a pool it started
 * itself has none. An unpooled source is wrapped. A source of any other kind may be a pool in disguise, and wrapping one
 * would let the drainer hold request capacity, so it is refused: define an {@code OutboxConnections} bean instead.
 * Credentials are read once: rotating them on the application pool needs a restart. Thread-safe.
 */
public final class OutboxConnections implements AutoCloseable {

    private static final String POOL_NAME = "platform-outbox";
    private static final long CONNECTION_TIMEOUT_MILLIS = 5000;
    private static final long VALIDATION_TIMEOUT_MILLIS = 3000;
    private static final String READ_TIMEOUT = "oracle.jdbc.ReadTimeout";
    private static final String CONNECT_TIMEOUT = "oracle.net.CONNECT_TIMEOUT";

    private final DataSource dataSource;
    private final AutoCloseable closer;

    /**
     * Connections the application provides itself.
     *
     * @param dataSource the drainer's connections, bounded and separate from the application's pool; never {@code null}
     * @param closer closes them at shutdown; never {@code null}
     */
    public OutboxConnections(DataSource dataSource, AutoCloseable closer) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.closer = Objects.requireNonNull(closer, "closer must not be null");
    }

    /**
     * Builds the drainer's pool.
     *
     * @param application the application's data source; never {@code null}
     * @param poolSize the most connections the drainer may hold
     * @param readTimeout the Oracle driver's read timeout on these connections; never {@code null}
     * @param registry receives the pool's meters, or {@code null} for none
     * @return the connections; never {@code null}
     * @throws IllegalStateException if {@code application} is neither a Hikari pool nor known to be unpooled
     */
    public static OutboxConnections of(DataSource application, int poolSize, Duration readTimeout, MeterRegistry registry) {
        HikariConfig config = new HikariConfig();
        HikariDataSource hikari = unwrapHikari(application);
        if (hikari != null && hikari.getJdbcUrl() != null) {
            config.setJdbcUrl(hikari.getJdbcUrl());
            config.setUsername(hikari.getUsername());
            config.setPassword(hikari.getPassword());
            if (hikari.getDriverClassName() != null) {
                config.setDriverClassName(hikari.getDriverClassName());
            }
            config.setDataSourceProperties(copy(hikari.getDataSourceProperties()));
            config.setConnectionInitSql(hikari.getConnectionInitSql());
            config.setSchema(hikari.getSchema());
            config.setCatalog(hikari.getCatalog());
            addOracleBounds(config, readTimeout);
        } else if (application instanceof EmbeddedDatabase || application instanceof AbstractDriverBasedDataSource) {
            config.setDataSource(application);
        } else {
            throw new IllegalStateException("The outbox drainer needs its own connections, and cannot derive them from a "
                    + application.getClass().getName() + ": wrapping a pool would let it hold request capacity. Define an "
                    + "OutboxConnections bean with a small pool of its own.");
        }
        config.setPoolName(POOL_NAME);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setValidationTimeout(VALIDATION_TIMEOUT_MILLIS);
        // Never fails startup on its own: Flyway and the drainer's table check report a missing database clearly.
        config.setInitializationFailTimeout(-1);
        if (registry != null) {
            config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        }
        HikariDataSource pool = new HikariDataSource(config);
        return new OutboxConnections(pool, pool);
    }

    private static void addOracleBounds(HikariConfig config, Duration readTimeout) {
        if (!config.getJdbcUrl().startsWith("jdbc:oracle:")) {
            return;
        }
        Properties properties = config.getDataSourceProperties();
        if (!properties.containsKey(READ_TIMEOUT) && System.getProperty(READ_TIMEOUT) == null) {
            properties.setProperty(READ_TIMEOUT, Long.toString(readTimeout.toMillis()));
        }
        if (!properties.containsKey(CONNECT_TIMEOUT) && System.getProperty(CONNECT_TIMEOUT) == null) {
            properties.setProperty(CONNECT_TIMEOUT, "10000");
        }
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
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
        return dataSource;
    }

    @Override
    public void close() throws Exception {
        closer.close();
    }
}
