package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.Kv;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.logstash.logback.argument.StructuredArguments;
import net.logstash.logback.marker.MapEntriesAppendingMarker;
import net.logstash.logback.marker.SingleFieldAppendingMarker;
import org.slf4j.helpers.MessageFormatter;

/**
 * Renders the formatted {@code message} from the same sanitized values the JSON fields carry, for
 * keyed message arguments: {@link Kv}, Logstash {@code kv(...)}/{@code v(...)}/{@code value(...)}
 * markers and {@code entries(...)}. Credential-keyed values become {@code ****} (value-only markers
 * render {@code ****}) whether or not {@code LogSanitizer} beans exist, without rendering the secret;
 * {@code LogSanitizer} beans apply to scalars, numbers, maps, collections and object arrays, nested in
 * any combination, keyed as in the JSON output (an array element by its enclosing field). Other objects
 * keep their {@code toString}; the pattern masks still run over the whole message.
 *
 * <p>Each argument is rendered under its own guard: one whose rendering throws becomes
 * {@value #UNRENDERABLE}, so neither the event nor a sibling's mask is lost and the exception text never
 * reaches a diagnostic channel. Returns an event-local view: the logged event and its argument array
 * are never mutated; when nothing changes, the original event is returned. Thread-safe.
 */
public final class SanitizedMessageView {

    static final String UNRENDERABLE = "[unrenderable]";

    private static final int MAX_DEPTH = 16;
    private static final Method FIELD_VALUE = accessor(SingleFieldAppendingMarker.class, "getFieldValue");
    private static final Field FORMAT_PATTERN = field(SingleFieldAppendingMarker.class, "messageFormatPattern");
    private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");

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
        if (arguments == null || arguments.length == 0) {
            return event;
        }
        String pattern = event.getMessage();
        int used = Math.min(arguments.length, placeholders(pattern));
        Object[] display = arguments.clone();
        boolean changed = false;
        for (int i = 0; i < used; i++) {
            Object rendered;
            try {
                rendered = displayed(arguments[i]);
            } catch (RuntimeException | LinkageError e) {
                rendered = UNRENDERABLE;
            }
            if (rendered != arguments[i]) {
                display[i] = rendered;
                changed = true;
            }
        }
        if (!changed) {
            return event;
        }
        String message = MessageFormatter.basicArrayFormat(pattern, display);
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

    // The argument itself when its rendering is unchanged, otherwise the text to show in its place.
    private static Object displayed(Object argument) {
        if (argument instanceof Kv kv) {
            if (PlatformValueMasker.isCredentialKey(kv.key())) {
                return kv.key() + "=" + PlatformValueMasker.MASK;
            }
            Object clean = sanitizedTree(kv.key(), kv.value(), 0);
            return clean == kv.value() ? guardedToString(argument) : kv.key() + "=" + StructuredArguments.toString(clean);
        }
        if (argument instanceof SingleFieldAppendingMarker marker) {
            String name = marker.getFieldName();
            if (PlatformValueMasker.isCredentialKey(name)) {
                return render(marker, name, PlatformValueMasker.MASK);
            }
            if (FIELD_VALUE == null) {
                return guardedToString(argument);
            }
            Object value = read(FIELD_VALUE, marker);
            Object clean = sanitizedTree(name, value, 0);
            return clean == value ? guardedToString(argument) : render(marker, name, StructuredArguments.toString(clean));
        }
        if (argument instanceof MapEntriesAppendingMarker entries && MAP_ENTRIES != null) {
            Object map = read(MAP_ENTRIES, entries);
            Object clean = sanitizedTree(null, map, 0);
            return clean == map ? guardedToString(argument) : String.valueOf(clean);
        }
        return argument;
    }

    // A structured argument whose own toString throws is shown as UNRENDERABLE, not handed to SLF4J.
    private static Object guardedToString(Object argument) {
        argument.toString();
        return argument;
    }

    /**
     * Applies credential masking and the {@code LogSanitizer} beans to a structured value: the same
     * instance when nothing changes, otherwise a sanitized copy (maps, collections and object arrays
     * become maps and lists).
     */
    static Object sanitizedTree(String key, Object value, int depth) {
        if (key != null && PlatformValueMasker.isCredentialKey(key)) {
            return PlatformValueMasker.MASK;
        }
        if (value == null || depth >= MAX_DEPTH) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object clean = sanitizedTree(String.valueOf(entry.getKey()), entry.getValue(), depth + 1);
                changed |= clean != entry.getValue();
                copy.put(entry.getKey(), clean);
            }
            return changed ? copy : value;
        }
        if (value instanceof Collection<?> || value instanceof Object[]) {
            Iterable<?> elements = value instanceof Object[] array ? List.of(array) : (Collection<?>) value;
            List<Object> copy = new ArrayList<>();
            boolean changed = false;
            for (Object element : elements) {
                Object clean = sanitizedTree(key, element, depth + 1);
                changed |= clean != element;
                copy.add(clean);
            }
            return changed ? copy : value;
        }
        if (key != null && (value instanceof CharSequence || value instanceof Number)
                && !LogSanitizerRegistry.current().isEmpty()) {
            String raw = value.toString();
            String clean = PlatformValueMasker.sanitize(key, raw);
            return clean.equals(raw) ? value : clean;
        }
        return value;
    }

    // As SingleFieldAppendingMarker.toStringSelf, with the given value text.
    private static String render(SingleFieldAppendingMarker marker, String name, String valueText) {
        Object pattern = FORMAT_PATTERN == null ? null : read(FORMAT_PATTERN, marker);
        if ("{1}".equals(pattern)) {
            return valueText;
        }
        return pattern instanceof String format ? MessageFormat.format(format, name, valueText) : name + "=" + valueText;
    }

    // Placeholders SLF4J fills: "{}" not escaped by a single backslash ("\\{}" is a backslash + "{}").
    private static int placeholders(String pattern) {
        if (pattern == null) {
            return 0;
        }
        int count = 0;
        for (int i = pattern.indexOf("{}"); i >= 0; i = pattern.indexOf("{}", i + 2)) {
            boolean escaped = i > 0 && pattern.charAt(i - 1) == '\\';
            boolean doubleEscaped = escaped && i > 1 && pattern.charAt(i - 2) == '\\';
            if (!escaped || doubleEscaped) {
                count++;
            }
        }
        return count;
    }

    private static Object read(Object accessor, Object target) {
        try {
            return accessor instanceof Method method ? method.invoke(target) : ((Field) accessor).get(target);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // logstash-logback-encoder keeps these protected/private; read them rather than re-implementing
    // every marker type's rendering. Absent (another encoder version): the safe fallbacks apply.
    private static Method accessor(Class<?> type, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
