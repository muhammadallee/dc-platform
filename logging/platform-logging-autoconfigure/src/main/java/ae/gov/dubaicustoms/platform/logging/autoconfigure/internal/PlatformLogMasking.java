package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.json.JsonWriter.MemberPath;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

/**
 * The platform's masking and strict MDC mode for Boot's structured JSON logging (D141). Registered in
 * {@code META-INF/spring.factories}, so Boot applies it alongside any customizer a service names in
 * {@code logging.structured.json.customizer}; a service never names this class.
 *
 * <p>Boot creates it when logging starts, before the application context, with the {@code Environment}:
 * <ul>
 *   <li>{@code dc.platform.logging.enabled=false}: does nothing (Boot's own logging, unchanged);</li>
 *   <li>{@code dc.platform.logging.redaction.enabled} (default {@code true}): masks every value with
 *       {@link PlatformValueMasker}, plus the patterns in {@code dc.platform.logging.redaction.patterns};</li>
 *   <li>{@code dc.platform.logging.mdc.include} (strict mode when not empty): writes only Boot's own top-level
 *       members, {@code service}, {@code traceId}, {@code spanId} and the listed keys. Boot writes MDC keys and
 *       key-value pairs alike as top-level members, so strict mode filters both.</li>
 * </ul>
 *
 * <p>Immutable once created; thread-safe.
 */
public final class PlatformLogMasking implements StructuredLoggingJsonMembersCustomizer<Object> {

    // Top-level members Boot's formats and the platform's defaults write, under their own and their renamed names.
    private static final Set<String> STANDARD_MEMBERS = Set.of("@timestamp", "@version", "message", "logger_name",
            "logger", "thread_name", "level", "level_value", "tags", "stack_trace", "service", "traceId", "spanId");

    private final boolean enabled;
    private final boolean redaction;
    private final Set<String> strictMembers;
    private final PlatformValueMasker masker;

    /**
     * Reads the logging switches.
     *
     * @param environment the application's environment, as Boot passes it when logging starts; never {@code null}
     */
    public PlatformLogMasking(Environment environment) {
        Binder binder = Binder.get(environment);
        this.enabled = environment.getProperty("dc.platform.logging.enabled", Boolean.class, true);
        this.redaction = environment.getProperty("dc.platform.logging.redaction.enabled", Boolean.class, true);
        List<String> include = binder.bind("dc.platform.logging.mdc.include", Bindable.listOf(String.class))
                .orElse(List.of());
        Set<String> strict = new LinkedHashSet<>();
        if (!include.isEmpty()) {
            strict.addAll(STANDARD_MEMBERS);
            include.stream().map(String::strip).filter(key -> !key.isEmpty()).forEach(strict::add);
        }
        this.strictMembers = Set.copyOf(strict);
        this.masker = new PlatformValueMasker(compile(binder.bind("dc.platform.logging.redaction.patterns",
                Bindable.listOf(String.class)).orElse(List.of())));
    }

    @Override
    public void customize(Members<Object> members) {
        if (!enabled) {
            return;
        }
        if (!strictMembers.isEmpty()) {
            members.applyingPathFilter(this::filteredOut);
        }
        if (redaction) {
            members.applyingValueProcessor(masker::mask);
        }
    }

    // Strict mode: a top-level member that is neither standard nor listed is left out.
    private boolean filteredOut(MemberPath path) {
        return PlatformValueMasker.topLevel(path) && !strictMembers.contains(path.name());
    }

    private static List<Pattern> compile(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(pattern.strip()));
            } catch (PatternSyntaxException invalid) {
                throw new IllegalStateException("dc.platform.logging.redaction.patterns holds an invalid regular "
                        + "expression: " + invalid.getDescription() + " at index " + invalid.getIndex(), invalid);
            }
        }
        return compiled;
    }
}
