package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Hands the application's {@link LogSanitizer} beans to {@link PlatformValueMasker}. Logback is
 * configured before the application context exists, so the encoder cannot look the beans up; this
 * bean publishes them once every singleton exists and withdraws them when the context closes.
 *
 * <p>One registry per JVM: with several contexts in one JVM (tests), the most recently started one
 * wins. Thread-safe (a volatile, immutable snapshot).
 */
public final class LogSanitizerRegistry implements SmartInitializingSingleton, DisposableBean {

    private static volatile List<LogSanitizer> current = List.of();

    private final ObjectProvider<LogSanitizer> sanitizers;
    private List<LogSanitizer> published = List.of();

    /**
     * Creates the registry.
     *
     * @param sanitizers the application's sanitizers, applied in {@code @Order}; never {@code null}
     */
    public LogSanitizerRegistry(ObjectProvider<LogSanitizer> sanitizers) {
        this.sanitizers = Objects.requireNonNull(sanitizers, "sanitizers must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        published = sanitizers.orderedStream().toList();
        current = published;
    }

    @Override
    public void destroy() {
        // Withdraw only our own snapshot: a later context may have published its own since.
        synchronized (LogSanitizerRegistry.class) {
            if (current == published) {
                current = List.of();
            }
        }
    }

    static List<LogSanitizer> current() {
        return current;
    }
}
