package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import java.util.Arrays;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Refuses out-of-order migrations outside the {@code local} profile (D112): applying an older version
 * after a newer one means environments end up with the same versions in different orders. Checked on
 * Flyway's effective configuration while it is built — before anything migrates — so the property,
 * {@code spring.flyway.*}, a {@code flyway.conf} or another customizer turning it on are all caught.
 * Runs last among the customizers. Thread-safe.
 */
public final class FlywayOutOfOrderGuard implements FlywayConfigurationCustomizer, Ordered {

    private final Environment environment;

    /**
     * @param environment the application environment (active profiles); never {@code null}
     */
    public FlywayOutOfOrderGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        if (configuration.isOutOfOrder() && !Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            throw new OutOfOrderMigrationsException("Flyway out-of-order migrations are enabled outside the "
                    + "'local' profile (active profiles: " + Arrays.toString(environment.getActiveProfiles())
                    + "); a shared database would apply versions in a different order than other environments.");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
