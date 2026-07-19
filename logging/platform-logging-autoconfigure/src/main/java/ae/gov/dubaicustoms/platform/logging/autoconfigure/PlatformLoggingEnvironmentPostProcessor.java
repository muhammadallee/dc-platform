package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Points Boot's logging system at the platform JSON logback configuration by defaulting
 * {@code logging.config} — registered in {@code META-INF/spring.factories}, the ONE legacy
 * registration the platform allows: logging must be configured before the application context
 * (and therefore before auto-configuration) exists.
 *
 * <p>Defaults land in a LOWEST-precedence property source named
 * {@code platform-logging-defaults}, so user configuration always wins and the source name is
 * visible in {@code /actuator/env} for debuggability (property-conventions §7).
 *
 * <p>Stateless and thread-safe.
 *
 * @since 0.1.0
 */
public class PlatformLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-logging-defaults";

    static final String CONFIG_LOCATION =
            "classpath:ae/gov/dubaicustoms/platform/logging/logback-platform.xml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("dc.platform.logging.enabled", Boolean.class, true)) {
            return;
        }
        if (!jsonFormatResolved(environment)) {
            // Console format = Boot's own logging defaults; nothing to contribute.
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("logging.config", CONFIG_LOCATION);
        // Resolved here (not in logback XML) because springProperty supports a single source:
        // explicit dc.platform.logging.service-name > spring.application.name > "application".
        defaults.put("dc.platform.logging.service-name",
                environment.getProperty("spring.application.name", "application"));
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /**
     * JSON is the default; console wins when explicitly configured OR when the {@code local}
     * profile is active and no explicit format was set — a developer terminal wants readable
     * lines, and profiles are the signal we already have (DX rationale, phase-04 spec).
     * Shared with {@code LoggingAutoConfiguration} so the banner reports the EFFECTIVE format.
     */
    static boolean jsonFormatResolved(Environment environment) {
        String format = environment.getProperty("dc.platform.logging.format");
        if (format != null) {
            return "json".equals(format.toLowerCase(Locale.ROOT));
        }
        return !Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor: application.yml/profile values must be
        // visible when the format decision is made.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
