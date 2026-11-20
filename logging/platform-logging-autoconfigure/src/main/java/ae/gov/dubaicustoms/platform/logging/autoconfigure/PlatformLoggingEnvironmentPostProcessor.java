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
 * Switches on Boot's structured JSON logging with the platform's field names (D141): registered in
 * {@code META-INF/spring.factories}, because logging is configured before the application context (and therefore
 * before auto-configuration) exists. The masking itself is {@code PlatformLogMasking}, a Boot JSON members customizer
 * registered there too.
 *
 * <p>In JSON mode it defaults, for the console and for a log file:
 * <ul>
 *   <li>{@code logging.structured.format.console} and {@code .file} to {@code logstash};</li>
 *   <li>{@code logger_name} renamed {@code logger}; {@code @version}, {@code thread_name} and {@code level_value}
 *       left out; a {@code service} member ({@code dc.platform.logging.service-name}, else
 *       {@code spring.application.name}, else {@code application});</li>
 *   <li>{@code logging.structured.json.context.include} from {@code dc.platform.logging.include-mdc};</li>
 *   <li>no ASCII banner, which would put non-JSON lines on stdout (D108).</li>
 * </ul>
 *
 * <p>Defaults land in a LOWEST-precedence property source named {@code platform-logging-defaults}, so user
 * configuration always wins and the source name is visible in {@code /actuator/env} (property-conventions §7).
 *
 * <p>Stateless and thread-safe.
 *
 * @since 0.1.0
 */
public class PlatformLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-logging-defaults";

    /** Boot's structured format the platform uses. */
    static final String FORMAT = "logstash";

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
        defaults.put("logging.structured.format.console", FORMAT);
        // A service that sets logging.file.name gets the same masked JSON, not Boot's plain pattern file.
        defaults.put("logging.structured.format.file", FORMAT);
        defaults.put("logging.structured.json.rename.logger_name", "logger");
        defaults.put("logging.structured.json.exclude", "@version,thread_name,level_value");
        defaults.put("logging.structured.json.add.service", serviceName(environment));
        defaults.put("logging.structured.json.context.include",
                environment.getProperty("dc.platform.logging.include-mdc", "true"));
        defaults.put("spring.main.banner-mode", "off");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    // dc.platform.logging.service-name > spring.application.name > "application".
    private static String serviceName(Environment environment) {
        String explicit = environment.getProperty("dc.platform.logging.service-name");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return environment.getProperty("spring.application.name", "application");
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
