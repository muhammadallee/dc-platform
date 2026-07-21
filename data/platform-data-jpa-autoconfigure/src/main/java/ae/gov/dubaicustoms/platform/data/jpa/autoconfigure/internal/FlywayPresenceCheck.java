package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.ClassUtils;

/**
 * Fails startup when JPA is configured with {@code dc.platform.data.jpa.require-migrations=true}
 * (the default) but Flyway is not on the classpath — the common "added JPA, forgot migrations"
 * misconfiguration that otherwise surfaces much later as a schema mismatch.
 *
 * <p>Detection is by class name only, so this module needs no compile dependency on Flyway (the
 * starter contributes {@code flyway-core}).
 */
public final class FlywayPresenceCheck implements InitializingBean {

    private static final String FLYWAY_CLASS = "org.flywaydb.core.Flyway";

    private final boolean requireMigrations;
    private final ClassLoader classLoader;

    /**
     * @param requireMigrations whether a missing Flyway should fail startup
     * @param classLoader the class loader to probe for Flyway
     */
    public FlywayPresenceCheck(boolean requireMigrations, ClassLoader classLoader) {
        this.requireMigrations = requireMigrations;
        this.classLoader = classLoader;
    }

    @Override
    public void afterPropertiesSet() {
        if (!requireMigrations) {
            return;
        }
        if (!ClassUtils.isPresent(FLYWAY_CLASS, classLoader)) {
            throw new IllegalStateException(
                    "JPA is configured but Flyway is not on the classpath, so schema migrations will "
                            + "not run. Add platform-starter-data-jpa (it bundles flyway-core) and put "
                            + "your migrations under classpath:db/migration, or set "
                            + "dc.platform.data.jpa.require-migrations=false to opt out.");
        }
    }
}
