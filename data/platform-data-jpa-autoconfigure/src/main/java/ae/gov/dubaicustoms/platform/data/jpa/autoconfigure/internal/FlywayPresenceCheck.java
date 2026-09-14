package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.ClassUtils;

/**
 * Fails startup when JPA is configured with {@code dc.platform.data.jpa.require-migrations=true}
 * (the default) but migrations cannot run — the common "added JPA, forgot migrations"
 * misconfiguration that otherwise surfaces much later as a schema mismatch.
 *
 * <p>Migrations need BOTH the Flyway engine ({@code flyway-core}) and Boot's Flyway integration: in
 * Boot 4 {@code FlywayAutoConfiguration} lives in {@code spring-boot-flyway}, so {@code flyway-core}
 * alone is an engine that is never started. Detection is by class name only, so this module needs no
 * compile dependency on either (the starter contributes both).
 */
public final class FlywayPresenceCheck implements InitializingBean {

    private static final String FLYWAY_CLASS = "org.flywaydb.core.Flyway";
    private static final String FLYWAY_AUTO_CONFIGURATION_CLASS =
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

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
            throw new MissingFlywayException(
                    "JPA is configured but Flyway is not on the classpath, so schema migrations will "
                            + "not run. Add platform-starter-data-jpa (it bundles flyway-core and "
                            + "spring-boot-flyway) and put your migrations under classpath:db/migration, or set "
                            + "dc.platform.data.jpa.require-migrations=false to opt out.");
        }
        if (!ClassUtils.isPresent(FLYWAY_AUTO_CONFIGURATION_CLASS, classLoader)) {
            throw new MissingFlywayException(
                    "JPA is configured and flyway-core is on the classpath, but Spring Boot's Flyway "
                            + "integration (org.springframework.boot:spring-boot-flyway) is not, so Flyway "
                            + "is never started and schema migrations will not run. Add "
                            + "platform-starter-data-jpa (it bundles both), or set "
                            + "dc.platform.data.jpa.require-migrations=false to opt out.");
        }
    }
}
