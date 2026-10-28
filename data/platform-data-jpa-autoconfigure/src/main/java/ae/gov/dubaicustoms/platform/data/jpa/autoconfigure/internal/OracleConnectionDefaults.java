package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Adds the Oracle driver's network bounds to a Hikari pool whose JDBC URL is {@code jdbc:oracle:}
 * (D110): a socket read timeout as the backstop for what a statement cancel cannot stop (a PL/SQL or
 * lock wait, a dead network) and a connect timeout for an unreachable host.
 *
 * <p>Applied to the pool bean after Boot has bound {@code spring.datasource.hikari.*} and before the
 * pool starts, keyed on the pool's own URL — not on an environment property (the URL can come from a
 * property source added after environment post-processing) and not on driver presence (an
 * Oracle-starter service runs on H2 locally). A value the application already set, as a
 * {@code data-source-properties} entry or a JVM system property, is left alone.
 *
 * <p>Thread-safe; stateless.
 */
public final class OracleConnectionDefaults implements BeanPostProcessor, Ordered {

    /** Above the 30 s transaction timeout, so a slow query is cancelled by that first. */
    static final Map<String, String> DEFAULTS = Map.of(
            "oracle.jdbc.ReadTimeout", "60000",
            "oracle.net.CONNECT_TIMEOUT", "10000");

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource pool && isOracle(pool.getJdbcUrl())) {
            DEFAULTS.forEach((key, value) -> {
                if (!pool.getDataSourceProperties().containsKey(key) && System.getProperty(key) == null) {
                    pool.addDataSourceProperty(key, value);
                }
            });
        }
        return bean;
    }

    private static boolean isOracle(String url) {
        return url != null && url.startsWith("jdbc:oracle:");
    }

    @Override
    public int getOrder() {
        // After the configuration-properties binding post-processor has populated the pool.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
