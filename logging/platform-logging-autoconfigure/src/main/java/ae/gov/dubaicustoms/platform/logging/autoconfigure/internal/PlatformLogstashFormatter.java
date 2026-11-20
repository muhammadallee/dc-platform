package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.structured.ContextPairs;
import org.springframework.boot.logging.structured.JsonWriterStructuredLogFormatter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

/**
 * The platform's JSON log line (D141): Boot's Logstash layout with the platform's field names, written by Boot's own
 * JSON writer. {@code PlatformLoggingEnvironmentPostProcessor} names it in {@code logging.structured.format.console}
 * and {@code .file}; Boot creates it with the parameters below and applies every members customizer (the platform's
 * masking, Boot's {@code logging.structured.json.*} properties, a service's own) as for its own formats.
 *
 * <p>Last, the finished line goes through {@code FinalLinePass}, so a value a service's own customizer or nested value
 * processor produced after the platform's processor is masked too.
 *
 * <p>Members: {@code @timestamp}, {@code message}, {@code logger}, {@code level}, the MDC and the event's key-value
 * pairs, {@code tags} (markers), {@code stack_trace}, {@code service}. Two things Boot's own format cannot do:
 * <ul>
 *   <li>the message is rendered from its pattern and its arguments, each argument masked by its structure first
 *       ({@link PlatformValueMasker#sanitize}): a map argument's credential names mask their whole values, which the
 *       text patterns over a formatted message cannot see;</li>
 *   <li>strict mode ({@code dc.platform.logging.mdc.include} not empty) filters the MDC and key-value pairs only, to
 *       {@code traceId}, {@code spanId} and the listed keys, so an unlisted key cannot pass under a name the layout
 *       itself uses.</li>
 * </ul>
 *
 * <p>Immutable; thread-safe.
 */
public final class PlatformLogstashFormatter extends JsonWriterStructuredLogFormatter<ILoggingEvent> {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final @Nullable PlatformValueMasker masker;

    /**
     * Created by Boot's structured-logging factory.
     *
     * @param environment the application's environment
     * @param stackTracePrinter Boot's stack-trace printer, when {@code logging.structured.json.stacktrace.*} sets one
     * @param contextPairs how Boot writes context pairs ({@code logging.structured.json.context.*})
     * @param throwableProxyConverter Logback's stack-trace rendering, used without a printer
     * @param customizers every members customizer Boot applies to its own formats
     */
    public PlatformLogstashFormatter(Environment environment, @Nullable StackTracePrinter stackTracePrinter,
            ContextPairs contextPairs, ThrowableProxyConverter throwableProxyConverter,
            StructuredLoggingJsonMembersCustomizer.Builder<?> customizers) {
        super(members -> members(environment, stackTracePrinter, contextPairs, throwableProxyConverter, members),
                customizers.build());
        this.masker = PlatformLogMasking.masker(environment);
    }

    // The finished line, after every customizer and nested value processor, through one last masking pass.
    @Override
    public String format(ILoggingEvent event) {
        String line = super.format(event);
        return masker == null ? line : FinalLinePass.mask(masker, line);
    }

    @Override
    public byte[] formatAsBytes(ILoggingEvent event, java.nio.charset.Charset charset) {
        return format(event).getBytes(charset);
    }

    private static void members(Environment environment, @Nullable StackTracePrinter stackTracePrinter,
            ContextPairs contextPairs, ThrowableProxyConverter throwableProxyConverter, Members<ILoggingEvent> members) {
        PlatformValueMasker masker = PlatformLogMasking.masker(environment);
        Set<String> strict = strictKeys(environment);
        String service = serviceName(environment);
        members.add("@timestamp", event -> TIMESTAMP.format(OffsetDateTime.ofInstant(event.getInstant(),
                ZoneId.systemDefault())));
        members.add("message", event -> message(event, masker));
        members.add("logger", ILoggingEvent::getLoggerName);
        members.add("level", event -> event.getLevel().toString());
        members.add().usingPairs(contextPairs.flat("_", pairs -> {
            pairs.addMapEntries(event -> mdc(event, strict));
            // Each key-value pair sanitized by its structure here, before Boot writes it (a WritableJson included).
            pairs.add(event -> keyValuePairs(event, strict), pair -> pair.key,
                    pair -> masker == null ? pair.value : masker.sanitizePair(pair.key, pair.value));
        }));
        members.add("tags", ILoggingEvent::getMarkerList).whenNotNull().as(PlatformLogstashFormatter::tags)
                .whenNotEmpty();
        members.add("stack_trace", event -> event).whenNotNull(ILoggingEvent::getThrowableProxy)
                .as(event -> stackTrace(event, stackTracePrinter, throwableProxyConverter));
        members.add("service", service);
    }

    // The message from its pattern, each argument masked by its structure first; the whole text is masked again as a
    // member. Without masking, Logback's own formatted message.
    static String message(ILoggingEvent event, @Nullable PlatformValueMasker masker) {
        Object[] arguments = event.getArgumentArray();
        if (masker == null || arguments == null || arguments.length == 0) {
            return event.getFormattedMessage();
        }
        Object[] display = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            display[i] = PlatformValueMasker.text(masker.sanitize(null, arguments[i]));
        }
        return MessageFormatter.basicArrayFormat(event.getMessage(), display);
    }

    private static Map<String, String> mdc(ILoggingEvent event, Set<String> strict) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (strict.isEmpty() || mdc == null) {
            return mdc;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        mdc.forEach((key, value) -> {
            if (strict.contains(key)) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    private static List<KeyValuePair> keyValuePairs(ILoggingEvent event, Set<String> strict) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (strict.isEmpty() || pairs == null) {
            return pairs == null ? List.of() : pairs;
        }
        return pairs.stream().filter(pair -> strict.contains(pair.key)).toList();
    }

    private static Set<String> tags(List<Marker> markers) {
        Set<String> tags = new LinkedHashSet<>();
        addMarkers(tags, markers.iterator());
        return tags;
    }

    private static void addMarkers(Set<String> tags, Iterator<Marker> markers) {
        while (markers.hasNext()) {
            Marker marker = markers.next();
            tags.add(marker.getName());
            if (marker.hasReferences()) {
                addMarkers(tags, marker.iterator());
            }
        }
    }

    private static String stackTrace(ILoggingEvent event, @Nullable StackTracePrinter printer,
            ThrowableProxyConverter converter) {
        IThrowableProxy proxy = event.getThrowableProxy();
        if (printer != null && proxy instanceof ch.qos.logback.classic.spi.ThrowableProxy throwable) {
            return printer.printStackTraceToString(throwable.getThrowable());
        }
        return converter.convert(event);
    }

    // Strict mode: traceId, spanId and the listed keys; empty when every key is written.
    private static Set<String> strictKeys(Environment environment) {
        List<String> include = Binder.get(environment)
                .bind("dc.platform.logging.mdc.include", Bindable.listOf(String.class)).orElse(List.of());
        Set<String> keys = new LinkedHashSet<>();
        include.stream().map(String::strip).filter(key -> !key.isEmpty()).forEach(keys::add);
        if (!keys.isEmpty()) {
            keys.add("traceId");
            keys.add("spanId");
        }
        return Set.copyOf(keys);
    }

    // dc.platform.logging.service-name > spring.application.name > "application".
    static String serviceName(Environment environment) {
        String explicit = environment.getProperty("dc.platform.logging.service-name");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return environment.getProperty("spring.application.name", "application");
    }
}
