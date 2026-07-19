package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

// Formats the startup capability banner; exists so the line format is unit-testable outside Spring.
public final class PlatformBanner {

    private PlatformBanner() {
    }

    /** One line, capabilities sorted by name: {@code platform: core[ACTIVE], messaging[ACTIVE] (kafka)}. */
    public static String format(List<CapabilityDescriptor> descriptors) {
        return descriptors.stream()
                .sorted(Comparator.comparing(CapabilityDescriptor::name))
                .map(descriptor -> descriptor.detail().isEmpty()
                        ? descriptor.name() + "[" + descriptor.status() + "]"
                        : descriptor.name() + "[" + descriptor.status() + "] (" + descriptor.detail() + ")")
                .collect(Collectors.joining(", ", "platform: ", ""));
    }
}
