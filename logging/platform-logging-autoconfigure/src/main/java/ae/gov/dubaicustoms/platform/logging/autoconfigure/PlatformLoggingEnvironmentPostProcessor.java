package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformLogstashFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li>{@code logging.structured.format.console} and {@code .file} to the platform's formatter,
 *       {@code PlatformLogstashFormatter}: the Logstash layout with the platform's field names and a {@code service}
 *       member;</li>
 *   <li>{@code logging.structured.json.context.include} from {@code dc.platform.logging.include-mdc};</li>
 *   <li>no ASCII banner, which would put non-JSON lines on stdout (D108).</li>
 * </ul>
 * In console mode, or switched off, it contributes nothing. Either way it first clears a Boot format system property
 * that holds the platform's own formatter (left by an earlier start in the same JVM), so this start's own choice
 * applies; any other value is left alone.
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

    /** The platform's structured format: the Logstash layout with the platform's field names. */
    static final String FORMAT = PlatformLogstashFormatter.class.getName();

    // Boot copies logging.structured.format.* into these JVM system properties when logging starts, and only when they
    // are not set yet; it never clears them.
    static final List<String> FORMAT_SYSTEM_PROPERTIES = List.of("CONSOLE_LOG_STRUCTURED_FORMAT",
            "FILE_LOG_STRUCTURED_FORMAT");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // A start earlier in this JVM leaves the platform's format in Boot's bridge properties. Cleared here, so this
        // start's own choice applies; Boot copies the platform's format in again if this start uses it too. A value
        // other than the platform's own formatter (a service's choice) is never touched.
        FORMAT_SYSTEM_PROPERTIES.stream().filter(name -> FORMAT.equals(System.getProperty(name)))
                .forEach(System::clearProperty);
        if (!environment.getProperty("dc.platform.logging.enabled", Boolean.class, true)
                || !jsonFormatResolved(environment)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("logging.structured.format.console", FORMAT);
        // A service that sets logging.file.name gets the same masked JSON, not Boot's plain pattern file.
        defaults.put("logging.structured.format.file", FORMAT);
        defaults.put("logging.structured.json.context.include",
                environment.getProperty("dc.platform.logging.include-mdc", "true"));
        defaults.put("spring.main.banner-mode", "off");
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
