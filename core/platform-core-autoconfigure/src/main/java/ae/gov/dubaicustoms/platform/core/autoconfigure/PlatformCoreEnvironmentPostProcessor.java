package ae.gov.dubaicustoms.platform.core.autoconfigure;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes the core capability's lowest-precedence defaults: turns on Spring Boot's own
 * context propagation for its task executors ({@code spring.task.execution.propagate-context}), so
 * the current trace, the {@code RequestContext}, and the MDC follow work onto {@code @Async} methods
 * and Boot-built executors. Applications override it like any other property.
 *
 * <p>Skipped entirely when {@code dc.platform.core.enabled=false}. Stateless and thread-safe.
 *
 * @since 0.1.0
 */
public class PlatformCoreEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-core-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("dc.platform.core.enabled", Boolean.class, true)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of("spring.task.execution.propagate-context", "true")));
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor: application.yml/profile values must be visible
        // when the kill switch is read.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
