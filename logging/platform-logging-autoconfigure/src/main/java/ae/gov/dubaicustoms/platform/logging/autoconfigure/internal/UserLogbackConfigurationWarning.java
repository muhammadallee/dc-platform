package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

/**
 * WARNs once at startup when the service's own Logback file replaces Boot's structured logging in JSON mode. Boot then
 * writes what that file says, and the platform's masking applies only if the file uses Boot's structured appender
 * ({@code org/springframework/boot/logging/logback/structured-console-appender.xml}). A documented limit (D141): the
 * platform cannot mask an appender it does not own.
 *
 * <p>Immutable; thread-safe.
 */
public final class UserLogbackConfigurationWarning implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(UserLogbackConfigurationWarning.class);

    // The files Boot's Logback system picks up by itself, in its own order.
    private static final List<String> STANDARD_FILES = List.of("logback-test.groovy", "logback-test.xml",
            "logback.groovy", "logback.xml", "logback-test-spring.groovy", "logback-test-spring.xml",
            "logback-spring.groovy", "logback-spring.xml");

    private final Environment environment;
    private final boolean json;

    /**
     * Creates the check.
     *
     * @param environment the application's environment; never {@code null}
     * @param json whether the effective platform log format is JSON
     */
    public UserLogbackConfigurationWarning(Environment environment, boolean json) {
        this.environment = environment;
        this.json = json;
    }

    @Override
    public void afterPropertiesSet() {
        userConfiguration().filter(file -> json).ifPresent(file -> LOG.warn("The Logback configuration {} replaces "
                + "Boot's structured logging, so the platform's log masking applies only if it uses Boot's structured "
                + "appender (structured-console-appender.xml). See docs/modules/logging.md.", file));
    }

    /**
     * The service's own Logback file, if it has one.
     *
     * @return {@code logging.config}, or the first standard Logback file on the classpath
     */
    Optional<String> userConfiguration() {
        String configured = environment.getProperty("logging.config");
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured);
        }
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        return STANDARD_FILES.stream().filter(file -> new ClassPathResource(file, loader).exists()).findFirst();
    }
}
