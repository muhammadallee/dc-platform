package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.DataJpaProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Adds the Oracle driver's network bounds to a Hikari pool whose JDBC URL is {@code jdbc:oracle:}
 * (D110): a socket read timeout as the backstop for what a statement cancel cannot stop (a PL/SQL or
 * lock wait, a dead network), {@code dc.platform.data.jpa.oracle.read-timeout}, and a 10 s connect timeout
 * for an unreachable host.
 *
 * <p>Applied to the pool bean after Boot has bound {@code spring.datasource.hikari.*} and before the
 * pool starts, keyed on the pool's own URL — not on an environment property (the URL can come from a
 * property source added after environment post-processing) and not on driver presence (an
 * Oracle-starter service runs on H2 locally). A value the application already set, as a
 * {@code data-source-properties} entry or a JVM system property, is left alone. A pool the application
 * started itself ({@code new HikariDataSource(config)}) is left alone too: its configuration is sealed.
 * The timeouts are read from the environment, not injected: a
 * {@code BeanPostProcessor} must not pull other beans in early.
 *
 * <p>Thread-safe; stateless (the environment is read for each pool).
 */
public final class OracleConnectionDefaults implements BeanPostProcessor, Ordered {

    static final String READ_TIMEOUT = "oracle.jdbc.ReadTimeout";
    static final String CONNECT_TIMEOUT = "oracle.net.CONNECT_TIMEOUT";
    private static final String CONNECT_TIMEOUT_MILLIS = "10000";

    private final Environment environment;

    public OracleConnectionDefaults(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        // A pool built with new HikariDataSource(config) has already started and sealed its configuration
        // (running, suspended or closed: getHikariPoolMXBean() is set from the start).
        if (bean instanceof HikariDataSource pool && pool.getHikariPoolMXBean() == null && !pool.isClosed()
                && isOracle(pool.getJdbcUrl())) {
            driverProperties(oracle(environment).readTimeout()).forEach((key, value) -> {
                if (!pool.getDataSourceProperties().containsKey(key) && System.getProperty(key) == null) {
                    pool.addDataSourceProperty(key, value);
                }
            });
        }
        return bean;
    }

    /** The two driver properties, in milliseconds, for a given read timeout. */
    static Map<String, String> driverProperties(Duration readTimeout) {
        return Map.of(READ_TIMEOUT, Long.toString(readTimeout.toMillis()), CONNECT_TIMEOUT, CONNECT_TIMEOUT_MILLIS);
    }

    /** The bound {@code dc.platform.data.jpa.oracle} values, with their defaults when unset. */
    static DataJpaProperties.Oracle oracle(Environment environment) {
        return Binder.get(environment).bindOrCreate("dc.platform.data.jpa.oracle", DataJpaProperties.Oracle.class);
    }

    static boolean isOracle(String url) {
        return url != null && url.startsWith("jdbc:oracle:");
    }

    @Override
    public int getOrder() {
        // After the configuration-properties binding post-processor has populated the pool.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
