package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/**
 * Adds the Oracle driver's network bounds (D110, the same values as {@link OracleConnectionDefaults}) to
 * Flyway's own data source. With {@code spring.flyway.user} or {@code spring.flyway.url} set — a separate
 * schema owner for migrations — Boot gives Flyway a {@code SimpleDriverDataSource} that is not a bean and
 * shares nothing with the pool, so without this a database that stops answering during migration would
 * block startup indefinitely.
 *
 * <p>Applied on the {@code Flyway} bean, whose configuration is complete, before anything connects. A
 * JVM system property of the same name wins (it is the only way to set driver properties on this data
 * source). Thread-safe; stateless.
 */
public final class OracleFlywayConnectionDefaults implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof Flyway flyway
                && flyway.getConfiguration().getDataSource() instanceof AbstractDriverBasedDataSource dataSource
                && dataSource.getUrl() != null && dataSource.getUrl().startsWith("jdbc:oracle:")) {
            Properties properties = new Properties();
            if (dataSource.getConnectionProperties() != null) {
                properties.putAll(dataSource.getConnectionProperties());
            }
            OracleConnectionDefaults.DEFAULTS.forEach((key, value) -> {
                if (!properties.containsKey(key) && System.getProperty(key) == null) {
                    properties.setProperty(key, value);
                }
            });
            dataSource.setConnectionProperties(properties);
        }
        return bean;
    }
}
