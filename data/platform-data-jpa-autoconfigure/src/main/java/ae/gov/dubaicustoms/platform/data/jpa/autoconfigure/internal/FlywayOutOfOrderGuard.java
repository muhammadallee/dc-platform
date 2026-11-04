package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

/**
 * Refuses out-of-order migrations outside the {@code local} profile (D112): applying an older version
 * after a newer one means environments end up with the same versions in different orders. Checked on the
 * {@code Flyway} bean's finished configuration — after every property, {@code flyway.conf} and
 * configuration customizer, whatever its order — and before Boot's migration initializer, which depends
 * on that bean, migrates anything. Thread-safe.
 */
public final class FlywayOutOfOrderGuard implements BeanPostProcessor {

    private final Environment environment;

    /**
     * @param environment the application environment (active profiles); never {@code null}
     */
    public FlywayOutOfOrderGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof Flyway flyway && flyway.getConfiguration().isOutOfOrder()
                && !Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            throw new OutOfOrderMigrationsException("Flyway out-of-order migrations are enabled outside the "
                    + "'local' profile (active profiles: " + Arrays.toString(environment.getActiveProfiles())
                    + "); a shared database would apply versions in a different order than other environments.");
        }
        return bean;
    }
}
