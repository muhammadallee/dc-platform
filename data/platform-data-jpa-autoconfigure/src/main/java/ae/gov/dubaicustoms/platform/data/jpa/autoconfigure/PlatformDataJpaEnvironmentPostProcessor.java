package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes the platform's Hibernate tuning as standard {@code spring.jpa.*} defaults: open-in-view
 * off, batched inserts/updates (size 50), and a UTC jdbc time zone — plus pool and timeout bounds
 * (D110: 5 s connection wait, 30 s transactions, leak detection under {@code local}) and Flyway's
 * safe settings pinned (D112). Snake_case physical naming is
 * Boot's own default, so it is deliberately left unset rather than pinned to a Boot-version-specific
 * strategy class.
 *
 * <p>Defaults land in a LOWEST-precedence property source named {@code platform-data-jpa-defaults},
 * so user configuration always wins and the source name is visible in {@code /actuator/env} for
 * debuggability (property-conventions §7). These are Boot's own keys, so no additional config
 * metadata is required. Registration is via {@code META-INF/spring.factories} — the only mechanism
 * Boot offers for an {@link EnvironmentPostProcessor}.
 *
 * <p>Stateless and thread-safe.
 *
 * @since 0.2.0
 */
public class PlatformDataJpaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-data-jpa-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("dc.platform.data.jpa.enabled", Boolean.class, true)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        // A request-scoped EntityManager kept open across view rendering hides lazy-loading costs
        // and holds a connection longer than needed; platform services render JSON, not views.
        defaults.put("spring.jpa.open-in-view", "false");
        defaults.put("spring.jpa.properties.hibernate.jdbc.batch_size", "50");
        defaults.put("spring.jpa.properties.hibernate.order_inserts", "true");
        defaults.put("spring.jpa.properties.hibernate.order_updates", "true");
        // Persist and read timestamps in UTC regardless of the JVM/db session zone.
        defaults.put("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC");
        poolAndTimeoutDefaults(environment, defaults);
        flywayDefaults(defaults);
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /*
     * D110, measured against Oracle (OracleExceptionPathsIT, OracleTimeoutLeversIT):
     * - a request waits at most 5 s for a pooled connection (Hikari's 30 s lets request threads pile
     *   up behind an exhausted pool); it then gets a 503 (D111);
     * - every transaction — Spring Data repository methods included — is bounded at 30 s; Spring
     *   hands the remaining time to each statement, which the driver cancels (ORA-01013);
     * - the Oracle driver's read and connect timeouts are added to the pool itself, keyed on its own
     *   URL (internal.OracleConnectionDefaults): the URL may come from a property source added after
     *   this post-processor ran;
     * - leak detection (a stack trace for a connection held over 20 s) only under the local profile.
     * Hikari's own keepalive (2 min) and max lifetime (30 min) defaults are kept.
     */
    private static void poolAndTimeoutDefaults(ConfigurableEnvironment environment, Map<String, Object> defaults) {
        defaults.put("spring.datasource.hikari.connection-timeout", "5000");
        defaults.put("spring.transaction.default-timeout", "30s");
        if (Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            defaults.put("spring.datasource.hikari.leak-detection-threshold", "20000");
        }
    }

    /*
     * D112: Flyway's safe defaults pinned rather than inherited, so a Flyway upgrade cannot loosen them —
     * never clean a database, validate applied migrations before migrating, apply in version order
     * only, and reject files that do not follow V<version>__<description>.sql instead of skipping them.
     */
    private static void flywayDefaults(Map<String, Object> defaults) {
        defaults.put("spring.flyway.clean-disabled", "true");
        defaults.put("spring.flyway.validate-on-migrate", "true");
        defaults.put("spring.flyway.out-of-order", "false");
        defaults.put("spring.flyway.validate-migration-naming", "true");
    }

    @Override
    public int getOrder() {
        // Lowest precedence: application.yml/profile values must win over these defaults.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
