package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.Kv;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import net.logstash.logback.marker.LogstashMarker;
import net.logstash.logback.marker.MapEntriesAppendingMarker;
import net.logstash.logback.marker.ObjectFieldsAppendingMarker;
import net.logstash.logback.marker.RawJsonAppendingMarker;
import net.logstash.logback.marker.SingleFieldAppendingMarker;
import org.slf4j.Marker;
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
 * <p>Values nested deeper than {@value #MAX_DEPTH} levels, and a container that contains itself, read
 * {@code ****} in the message. Each structured argument is rendered exactly once, here, under its own
 * guard — never through Logstash's {@code StructuredArguments.toString}, which reports a throwing value's
 * exception to the Logback status manager unmasked: one whose rendering throws becomes
 * {@value #UNRENDERABLE}, so neither the event nor a sibling's mask is lost and the exception text never
 * reaches a diagnostic channel. Returns an event-local view: the logged event and its argument array
 * are never mutated; when nothing changes, the original event is returned. Thread-safe.
 */
public final class SanitizedMessageView {

    static final String UNRENDERABLE = "[unrenderable]";

    private static final int MAX_DEPTH = 64;
    private static final Method FIELD_VALUE = accessor(SingleFieldAppendingMarker.class, "getFieldValue");
    private static final Field FORMAT_PATTERN = field(SingleFieldAppendingMarker.class, "messageFormatPattern");
    private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");
    private static final Field OBJECT_FIELDS = field(ObjectFieldsAppendingMarker.class, "object");
    private static final Method SELF_TEXT = accessor(LogstashMarker.class, "toStringSelf");

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
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable e) {
                // Whatever SLF4J's own formatting would have survived (assertion errors, a recursive
                // toString's StackOverflowError) is survived here too, without reporting the text.
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

    // The text to show in the argument's place (an unstructured argument is left to SLF4J).
    private static Object displayed(Object argument) {
        return displayed(argument, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    // Also used for a Kv or marker nested inside a structured value, with the enclosing depth and path.
    private static Object displayed(Object argument, int depth, Set<Object> path) {
        if (argument instanceof Kv kv) {
            if (PlatformValueMasker.isCredentialKey(kv.key())) {
                return kv.key() + "=" + PlatformValueMasker.MASK;
            }
            Object clean = sanitizedTree(kv.key(), kv.value(), depth, path);
            return clean == kv.value() ? guardedToString(argument) : kv.key() + "=" + valueText(clean);
        }
        if (argument instanceof LogstashMarker marker) {
            if (depth >= MAX_DEPTH || !path.add(marker)) {
                return PlatformValueMasker.MASK;
            }
            try {
                return markerText(marker, depth, path);
            } finally {
                path.remove(marker);
            }
        }
        return argument;
    }

    // As LogstashMarker.toString — its own text, then each referenced marker's, ", "-joined — rendered
    // here even when nothing changes: Logstash's own rendering goes through StructuredArguments.toString.
    private static String markerText(LogstashMarker marker, int depth, Set<Object> path) {
        String self = selfText(marker, depth, path);
        if (!marker.hasReferences()) {
            return self;
        }
        StringBuilder text = new StringBuilder(self);
        boolean separator = !self.isEmpty();
        for (Marker reference : marker) {
            if (separator) {
                text.append(", ");
            }
            String referenceText = String.valueOf(displayed(reference, depth + 1, path));
            if (!referenceText.isEmpty()) {
                text.append(referenceText);
                separator = true;
            }
        }
        return text.toString();
    }

    // A reflective accessor missing (another encoder version) shows the value masked, never unrendered.
    private static String selfText(LogstashMarker marker, int depth, Set<Object> path) {
        if (marker instanceof SingleFieldAppendingMarker field) {
            String name = field.getFieldName();
            if (PlatformValueMasker.isCredentialKey(name) || FIELD_VALUE == null) {
                return render(field, name, PlatformValueMasker.MASK);
            }
            Object value = read(FIELD_VALUE, field);
            if (marker instanceof RawJsonAppendingMarker && value instanceof String raw) {
                return render(field, name, rawText(name, raw, depth));
            }
            return render(field, name, valueText(sanitizedTree(name, value, depth, path)));
        }
        if (marker instanceof MapEntriesAppendingMarker entries) {
            return MAP_ENTRIES == null ? PlatformValueMasker.MASK
                    : String.valueOf(sanitizedTree(null, read(MAP_ENTRIES, entries), depth, path));
        }
        if (marker instanceof ObjectFieldsAppendingMarker fields) {
            // fields(bean): the JSON fields are sanitized one by one; the message keeps the bean's text
            // (a collection or map of structured arguments is traversed like any value).
            return OBJECT_FIELDS == null ? PlatformValueMasker.MASK
                    : valueText(sanitizedTree(null, read(OBJECT_FIELDS, fields), depth, path));
        }
        // Other markers (the empty marker, consumer subclasses) render their own text; in encoder 8.1 only
        // the two types above go through StructuredArguments.toString.
        return SELF_TEXT == null ? "" : String.valueOf(read(SELF_TEXT, marker));
    }

    // raw(...): read as one JSON value (RawJson) and shown decoded, like any structured value — names and
    // strings unescaped, so the pattern masks over the message see them as written. Text that is not
    // exactly one JSON value is shown as is (the pattern masks still run over the message).
    private static String rawText(String name, String raw, int depth) {
        Object parsed;
        try {
            parsed = RawJson.parse(raw);
        } catch (IOException e) {
            return raw;
        }
        return valueText(rawClean(name, parsed, depth));
    }

    // As sanitizedTree, over a parsed raw value (duplicate members kept).
    private static Object rawClean(String key, Object node, int depth) {
        if (key != null && PlatformValueMasker.isCredentialKey(key) || depth >= MAX_DEPTH) {
            return PlatformValueMasker.MASK;
        }
        if (node instanceof RawJson.RawObject object) {
            RawJson.RawObject copy = new RawJson.RawObject();
            object.members.forEach(member ->
                    copy.add(member.getKey(), rawClean(member.getKey(), member.getValue(), depth + 1)));
            return copy;
        }
        if (node instanceof List<?> array) {
            List<Object> copy = new ListCopy();
            array.forEach(element -> copy.add(rawClean(key, element, depth + 1)));
            return copy;
        }
        return sanitizedTree(key, node, depth);
    }

    // A structured argument is rendered exactly once, here, under the caller's guard: handing it back to
    // SLF4J would render it a second time, outside the guard.
    private static Object guardedToString(Object argument) {
        return String.valueOf(argument);
    }

    // As StructuredArguments.toString (arrays by content), minus its catch: a throwing value reaches the
    // caller's guard instead of the Logback status manager.
    private static String valueText(Object value) {
        return switch (value) {
            case null -> "null";
            case Object[] array -> {
                StringJoiner text = new StringJoiner(", ", "[", "]");
                for (Object element : array) {
                    text.add(valueText(element));
                }
                yield text.toString();
            }
            case byte[] array -> Arrays.toString(array);
            case short[] array -> Arrays.toString(array);
            case int[] array -> Arrays.toString(array);
            case long[] array -> numbers(Arrays.stream(array).boxed().toList());
            case char[] array -> Arrays.toString(array);
            case float[] array -> numbers(floats(array));
            case double[] array -> numbers(Arrays.stream(array).boxed().toList());
            case boolean[] array -> Arrays.toString(array);
            case RawJson.RawObject object -> {
                StringJoiner text = new StringJoiner(", ", "{", "}");
                object.members.forEach(member -> text.add(member.getKey() + "=" + valueText(member.getValue())));
                yield text.toString();
            }
            default -> String.valueOf(value);
        };
    }

    // Primitive number arrays by content, each element through the numeric card check (the JSON array's
    // elements are masked one by one too).
    private static String numbers(List<? extends Number> elements) {
        StringJoiner text = new StringJoiner(", ", "[", "]");
        elements.forEach(number -> text.add(PlatformValueMasker.cardNumber(number) ? PlatformValueMasker.MASK : String.valueOf(number)));
        return text.toString();
    }

    private static List<Float> floats(float[] array) {
        List<Float> boxed = new ArrayList<>(array.length);
        for (float element : array) {
            boxed.add(element);
        }
        return boxed;
    }

    // The sanitized copies show their elements as valueText does (arrays by content, at any nesting);
    // collections the caller passed in, unchanged, keep their own toString.
    private static final class ListCopy extends ArrayList<Object> {

        @Override
        public String toString() {
            StringJoiner text = new StringJoiner(", ", "[", "]");
            forEach(element -> text.add(valueText(element)));
            return text.toString();
        }
    }

    private static final class MapCopy extends LinkedHashMap<Object, Object> {

        @Override
        public String toString() {
            StringJoiner text = new StringJoiner(", ", "{", "}");
            forEach((key, value) -> text.add(key + "=" + valueText(value)));
            return text.toString();
        }
    }

    /**
     * Applies credential masking and the {@code LogSanitizer} beans to a structured value: the same
     * instance when nothing changes, otherwise a sanitized copy (maps, collections and object arrays
     * become maps and lists).
     */
    static Object sanitizedTree(String key, Object value, int depth) {
        return sanitizedTree(key, value, depth, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    // path: the containers being traversed, by identity, so a container holding itself ends the descent.
    private static Object sanitizedTree(String key, Object value, int depth, Set<Object> path) {
        if (key != null && PlatformValueMasker.isCredentialKey(key)) {
            return PlatformValueMasker.MASK;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Kv || value instanceof LogstashMarker) {
            // A structured argument nested in a value: rendered here like a top-level one, never through
            // its own toString.
            return displayed(value, depth + 1, path);
        }
        boolean container = value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof Object[];
        if (depth >= MAX_DEPTH || container && path.contains(value)) {
            // Deeper than the traversal goes, or a cycle: shown masked rather than unchecked.
            return PlatformValueMasker.MASK;
        }
        if (value instanceof Map<?, ?> map) {
            path.add(value);
            Map<Object, Object> copy = new MapCopy();
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object clean = sanitizedTree(String.valueOf(entry.getKey()), entry.getValue(), depth + 1, path);
                changed |= clean != entry.getValue();
                copy.put(entry.getKey(), clean);
            }
            path.remove(value);
            return changed ? copy : value;
        }
        if (container) {
            path.add(value);
            Iterable<?> elements = value instanceof Object[] array ? Arrays.asList(array) : (Collection<?>) value;
            List<Object> copy = new ListCopy();
            boolean changed = false;
            for (Object element : elements) {
                Object clean = sanitizedTree(key, element, depth + 1, path);
                changed |= clean != element;
                copy.add(clean);
            }
            path.remove(value);
            if (!changed) {
                return value;
            }
            // An array stays an array, so it is shown by content (deepToString) as before.
            return value instanceof Object[] ? copy.toArray() : copy;
        }
        // LogSanitizer beans first, then the numeric card check — the JSON field's order.
        if (key != null && (value instanceof CharSequence || value instanceof Number)
                && !LogSanitizerRegistry.current().isEmpty()) {
            String raw = value.toString();
            String clean = PlatformValueMasker.sanitize(key, raw);
            if (!clean.equals(raw)) {
                return clean;
            }
        }
        // As the JSON number masker: a double's text (4.111111111111111E15) escapes the text masks.
        return value instanceof Number number && PlatformValueMasker.cardNumber(number) ? PlatformValueMasker.MASK : value;
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
