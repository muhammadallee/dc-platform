package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes the platform's Hibernate tuning as standard {@code spring.jpa.*} defaults: open-in-view
 * off, batched inserts/updates (size 50), and a UTC jdbc time zone. Snake_case physical naming is
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
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        // Lowest precedence: application.yml/profile values must win over these defaults.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
