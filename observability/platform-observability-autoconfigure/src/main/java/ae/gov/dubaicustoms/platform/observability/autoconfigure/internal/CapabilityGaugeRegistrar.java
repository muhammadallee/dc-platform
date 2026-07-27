package ae.gov.dubaicustoms.platform.observability.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Registers a {@code platform.capability.active{capability=...}} gauge (1 when the capability's
 * {@link CapabilityDescriptor} reports {@code ACTIVE}, else 0) once every capability bean exists —
 * the adoption-telemetry signal (phase-16 F.1). Runs as a {@link SmartInitializingSingleton} so it
 * fires after all descriptors have been contributed; the value is fixed at startup, so each gauge is
 * a constant strong-referenced by the descriptor bean the context holds.
 */
public final class CapabilityGaugeRegistrar implements SmartInitializingSingleton {

    /** Meter name for the per-capability active flag. */
    public static final String METRIC = "platform.capability.active";

    private final MeterRegistry registry;
    private final ObjectProvider<CapabilityDescriptor> descriptors;

    /**
     * @param registry the meter registry to register gauges on
     * @param descriptors every capability's {@link CapabilityDescriptor} bean
     */
    public CapabilityGaugeRegistrar(MeterRegistry registry, ObjectProvider<CapabilityDescriptor> descriptors) {
        this.registry = registry;
        this.descriptors = descriptors;
    }

    @Override
    public void afterSingletonsInstantiated() {
        descriptors.orderedStream().forEach(descriptor ->
                Gauge.builder(METRIC, descriptor, d -> "ACTIVE".equalsIgnoreCase(d.status()) ? 1.0 : 0.0)
                        .tag("capability", descriptor.name())
                        .description("1 when the platform capability is active, else 0")
                        .strongReference(true)
                        .register(registry));
    }
}
