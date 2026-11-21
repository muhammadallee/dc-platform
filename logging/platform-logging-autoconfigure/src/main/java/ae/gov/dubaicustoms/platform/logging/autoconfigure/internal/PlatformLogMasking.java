package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.json.JsonWriter.MemberPath;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

/**
 * The platform's masking for Boot's structured JSON logging, in every format (D141). Registered in
 * {@code META-INF/spring.factories}, so Boot applies it alongside any customizer a service names in
 * {@code logging.structured.json.customizer}; a service never names this class.
 *
 * <p>Boot creates it when logging starts, before the application context, with the {@code Environment}. It masks
 * every value with {@link PlatformValueMasker} (plus the patterns in {@code dc.platform.logging.redaction.patterns}),
 * and a member that {@code logging.structured.json.rename} renames to a credential name is masked whole. It does
 * nothing when {@code dc.platform.logging.enabled} or {@code dc.platform.logging.redaction.enabled} is {@code false};
 * the patterns are then not even read.
 *
 * <p>Immutable once created; thread-safe.
 */
public final class PlatformLogMasking implements StructuredLoggingJsonMembersCustomizer<Object> {

    private final PlatformValueMasker masker;
    private final Map<String, String> renames;
    private final String contextPrefix;

    /**
     * Reads the logging switches.
     *
     * @param environment the application's environment, as Boot passes it when logging starts; never {@code null}
     */
    public PlatformLogMasking(Environment environment) {
        this.masker = masker(environment);
        this.renames = masker == null ? Map.of() : Binder.get(environment)
                .bind("logging.structured.json.rename", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        this.contextPrefix = environment.getProperty("logging.structured.json.context.prefix", "");
    }

    @Override
    public void customize(Members<Object> members) {
        if (masker != null) {
            members.applyingValueProcessor((MemberPath path, Object value) ->
                    renamedToACredential(path) || prefixedCredential(path) ? PlatformValueMasker.MASK
                            : masker.mask(path, value));
        }
    }

    /**
     * The masker the switches call for: {@code null} when logging or redaction is switched off.
     *
     * @param environment the application's environment; never {@code null}
     * @return the masker, or {@code null}
     * @throws IllegalStateException when an extra pattern is not a valid regular expression
     */
    public static PlatformValueMasker masker(Environment environment) {
        if (!environment.getProperty("dc.platform.logging.enabled", Boolean.class, true)
                || !environment.getProperty("dc.platform.logging.redaction.enabled", Boolean.class, true)) {
            return null;
        }
        java.util.Set<String> formatObjects = new java.util.HashSet<>();
        for (String key : List.of("logging.structured.format.console", "logging.structured.format.file")) {
            String format = environment.getProperty(key, "");
            if ("ecs".equalsIgnoreCase(format)) {
                formatObjects.addAll(PlatformValueMasker.ECS_OBJECTS);
            }
            if ("gelf".equalsIgnoreCase(format)) {
                formatObjects.addAll(PlatformValueMasker.GELF_OBJECTS);
            }
        }
        return new PlatformValueMasker(compile(Binder.get(environment)
                .bind("dc.platform.logging.redaction.patterns", Bindable.listOf(String.class)).orElse(List.of())),
                formatObjects);
    }

    // Boot keeps the original name in the path and writes the renamed one: the renamed name counts too.
    private boolean renamedToACredential(MemberPath path) {
        if (renames.isEmpty()) {
            return false;
        }
        for (MemberPath p = path; p != null && p.parent() != null; p = p.parent()) {
            String target = renames.get(p.toUnescapedString());
            if (target != null && PlatformValueMasker.isCredentialKey(target.substring(target.lastIndexOf('.') + 1))) {
                return true;
            }
        }
        return false;
    }

    // Boot writes a context entry under logging.structured.json.context.prefix, joined with a separator unless the
    // prefix ends with one or the name starts with one (ctx + pwd = ctx_pwd; ctx_ + pwd = ctx_pwd); GELF adds a leading
    // underscore. The name without them counts too.
    private boolean prefixedCredential(MemberPath path) {
        if (contextPrefix.isEmpty() || !PlatformValueMasker.topLevel(path) || path.name() == null) {
            return false;
        }
        String name = path.name();
        for (String candidate : List.of(name, name.startsWith("_") ? name.substring(1) : name)) {
            if (candidate.startsWith(contextPrefix)) {
                String rest = candidate.substring(contextPrefix.length());
                if (rest.startsWith("_") || rest.startsWith(".")) {
                    rest = rest.substring(1);
                }
                if (PlatformValueMasker.isCredentialKey(rest)) {
                    return true;
                }
            }
        }
        return false;
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
