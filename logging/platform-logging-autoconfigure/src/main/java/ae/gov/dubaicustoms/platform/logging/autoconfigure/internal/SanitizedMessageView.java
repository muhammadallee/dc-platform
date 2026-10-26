package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.Kv;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import net.logstash.logback.marker.SingleFieldAppendingMarker;
import org.slf4j.helpers.MessageFormatter;

/**
 * Applies {@code LogSanitizer} beans to keyed message arguments ({@link Kv} and Logstash
 * {@code kv(...)}/{@code StructuredArguments} markers) before the message is rendered, so a sanitized
 * value does not survive in the formatted {@code message} while its structured field is clean.
 *
 * <p>Returns an event-local view: the logged event and its argument array are never mutated. Without
 * registered sanitizers, or when no argument changes, the original event is returned. Thread-safe.
 */
public final class SanitizedMessageView {

    private static final Method FIELD_VALUE = fieldValueAccessor();

    private SanitizedMessageView() {
    }

    /**
     * Returns the event to encode.
     *
     * @param event the logged event; never {@code null}
     * @return {@code event}, or a view whose formatted message is rendered from sanitized arguments
     */
    public static ILoggingEvent of(ILoggingEvent event) {
        Object[] arguments = event.getArgumentArray();
        if (arguments == null || arguments.length == 0 || LogSanitizerRegistry.current().isEmpty()) {
            return event;
        }
        Object[] display = arguments.clone();
        boolean changed = false;
        for (int i = 0; i < display.length; i++) {
            Object sanitized = sanitized(display[i]);
            if (sanitized != display[i]) {
                display[i] = sanitized;
                changed = true;
            }
        }
        if (!changed) {
            return event;
        }
        String message = MessageFormatter.arrayFormat(event.getMessage(), display).getMessage();
        return (ILoggingEvent) Proxy.newProxyInstance(ILoggingEvent.class.getClassLoader(),
                new Class<?>[] {ILoggingEvent.class}, (proxy, method, args) -> {
                    if ("getFormattedMessage".equals(method.getName()) && method.getParameterCount() == 0) {
                        return message;
                    }
                    try {
                        return method.invoke(event, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Object sanitized(Object argument) {
        if (argument instanceof Kv kv) {
            String raw = String.valueOf(kv.value());
            String clean = PlatformValueMasker.sanitize(kv.key(), raw);
            return clean.equals(raw) ? argument : Kv.of(kv.key(), clean);
        }
        if (argument instanceof SingleFieldAppendingMarker marker && FIELD_VALUE != null) {
            String raw = String.valueOf(fieldValue(marker));
            String clean = PlatformValueMasker.sanitize(marker.getFieldName(), raw);
            // Keeps the marker's own rendering ("key=value", "value", custom pattern) with the clean value.
            return clean.equals(raw) ? argument : marker.toString().replace(raw, clean);
        }
        return argument;
    }

    private static Object fieldValue(SingleFieldAppendingMarker marker) {
        try {
            return FIELD_VALUE.invoke(marker);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // getFieldValue() is protected in logstash-logback-encoder; read it rather than re-implementing
    // every marker type's rendering.
    private static Method fieldValueAccessor() {
        try {
            Method method = SingleFieldAppendingMarker.class.getDeclaredMethod("getFieldValue");
            method.setAccessible(true);
            return Objects.requireNonNull(method);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
