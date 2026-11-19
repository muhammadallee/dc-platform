package ae.gov.dubaicustoms.platform.core.autoconfigure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Applies a service's {@code dc.platform.core.capabilities.<name>.status} and {@code .detail} properties to the
 * capability descriptors the platform reports (the startup banner, {@code /actuator/platform}, the capability gauge).
 * A property replaces the status or detail of the descriptor with that name, or adds a descriptor when none has it.
 * This is how a service changes what is reported without a platform type in its code (rule 12).
 *
 * <p>Platform infrastructure: services set the properties, never call this class. Stateless and thread-safe.
 */
public final class CapabilityOverrides {

    /** The property prefix the overrides bind from. */
    public static final String PREFIX = "dc.platform.core.capabilities";

    // name -> {status, detail}
    private static final Bindable<Map<String, Map<String, String>>> OVERRIDES = Bindable.of(
            ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class),
                    ResolvableType.forClassWithGenerics(Map.class, String.class, String.class)));

    private CapabilityOverrides() {
    }

    /**
     * Returns the descriptors to report: the beans, with the environment's overrides applied, sorted by name.
     *
     * @param descriptors the descriptor beans in the context; never {@code null}
     * @param environment the environment to read overrides from; {@code null} applies none
     * @return the descriptors to report; never {@code null}
     */
    public static List<CapabilityDescriptor> apply(List<CapabilityDescriptor> descriptors, Environment environment) {
        Map<String, CapabilityDescriptor> byName = new LinkedHashMap<>();
        descriptors.forEach(descriptor -> byName.put(descriptor.name(), descriptor));
        Map<String, Map<String, String>> overrides = environment == null ? Map.of()
                : Binder.get(environment).<Map<String, Map<String, String>>>bind(PREFIX, OVERRIDES).orElse(Map.of());
        overrides.forEach((name, values) -> {
            CapabilityDescriptor current = byName.get(name);
            String status = values.getOrDefault("status", current == null ? "ACTIVE" : current.status());
            String detail = values.getOrDefault("detail", current == null ? "" : current.detail());
            byName.put(name, new CapabilityDescriptor(name, status, detail));
        });
        List<CapabilityDescriptor> result = new ArrayList<>(byName.values());
        result.sort(Comparator.comparing(CapabilityDescriptor::name));
        return List.copyOf(result);
    }
}
