package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes what a Rabbit send needs to be acknowledged (D119) as Boot's own {@code spring.rabbitmq.*}
 * defaults: publisher confirms, returns and {@code mandatory}, without which a send can be lost silently,
 * and a 5 s connection timeout (Boot's client default of 60 s would hold a sender that long).
 *
 * <p>Defaults land in a LOWEST-precedence property source named {@code platform-messaging-defaults}, so user
 * configuration wins; {@code RabbitEventTransport} refuses to start if confirms, returns or
 * {@code mandatory} end up off. Registered via {@code META-INF/spring.factories}. Stateless and thread-safe.
 *
 * @since 1.0.0
 */
public class PlatformMessagingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable: operators look for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-messaging-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("dc.platform.messaging.enabled", Boolean.class, true)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.rabbitmq.publisher-confirm-type", "correlated");
        defaults.put("spring.rabbitmq.publisher-returns", "true");
        defaults.put("spring.rabbitmq.template.mandatory", "true");
        defaults.put("spring.rabbitmq.connection-timeout", "5s");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        // Lowest precedence: application.yml/profile values must win over these defaults.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
