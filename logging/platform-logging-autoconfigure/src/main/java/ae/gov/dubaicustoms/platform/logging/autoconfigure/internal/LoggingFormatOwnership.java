package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.autoconfigure.PlatformLoggingEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

/**
 * Records which structured-format system properties Boot actually filled from a start the platform configured, with
 * the values it wrote, right after Boot's logging has started (Codex phase 3 round 1, verification 2). The next start
 * in the same JVM clears exactly those, while they still hold those values; a property a service set itself is never
 * recorded. Registered in {@code META-INF/spring.factories}; ordered just after Boot's {@code LoggingApplicationListener}.
 *
 * <p>Stateless; thread-safe.
 */
public final class LoggingFormatOwnership implements GenericApplicationListener {

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType.toClass());
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        for (String name : PlatformLoggingEnvironmentPostProcessor.unsetBeforeLogging()) {
            String written = System.getProperty(name);
            if (written != null) {
                System.setProperty(PlatformLoggingEnvironmentPostProcessor.OWNED_PREFIX + name, written);
            }
        }
    }

    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }
}
