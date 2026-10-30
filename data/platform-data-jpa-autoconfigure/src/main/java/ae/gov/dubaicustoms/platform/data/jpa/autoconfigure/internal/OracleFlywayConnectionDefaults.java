package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/**
 * Adds the Oracle driver's network bounds (D110) to Flyway's own data source: the 10 s connect timeout of
 * {@link OracleConnectionDefaults} and a longer read timeout, {@code dc.platform.data.jpa.oracle.flyway-read-timeout}
 * (10 min), so a long migration statement can finish while a database that stops answering still fails startup.
 * With {@code spring.flyway.user} or {@code spring.flyway.url} set — a separate schema owner for migrations —
 * Boot gives Flyway a {@code SimpleDriverDataSource} that is not a bean and shares nothing with the pool, so
 * without this a silent database would block startup indefinitely.
 *
 * <p>Applied on the {@code Flyway} bean, whose configuration is complete, before Flyway connects (with a
 * {@code {vendor}} location Boot has already opened a detection connection without them; see data.md). A
 * JVM system property of the same name wins (it is the only other way to set driver properties on this data
 * source). Thread-safe; stateless.
 */
public final class OracleFlywayConnectionDefaults implements BeanPostProcessor {

    private final Environment environment;

    public OracleFlywayConnectionDefaults(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof Flyway flyway
                && flyway.getConfiguration().getDataSource() instanceof AbstractDriverBasedDataSource dataSource
                && OracleConnectionDefaults.isOracle(dataSource.getUrl())) {
            Properties properties = new Properties();
            if (dataSource.getConnectionProperties() != null) {
                properties.putAll(dataSource.getConnectionProperties());
            }
            OracleConnectionDefaults.driverProperties(OracleConnectionDefaults.oracle(environment).flywayReadTimeout())
                    .forEach((key, value) -> {
                        if (!properties.containsKey(key) && System.getProperty(key) == null) {
                            properties.setProperty(key, value);
                        }
                    });
            dataSource.setConnectionProperties(properties);
        }
        return bean;
    }
}
