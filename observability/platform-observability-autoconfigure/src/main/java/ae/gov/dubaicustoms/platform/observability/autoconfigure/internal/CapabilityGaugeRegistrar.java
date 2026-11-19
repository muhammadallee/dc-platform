package ae.gov.dubaicustoms.platform.observability.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.autoconfigure.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.core.autoconfigure.CapabilityOverrides;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

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
    private final Environment environment;

    /**
     * @param registry the meter registry to register gauges on
     * @param descriptors every capability's {@link CapabilityDescriptor} bean
     * @param environment the environment whose {@code dc.platform.core.capabilities.*} overrides apply
     */
    public CapabilityGaugeRegistrar(MeterRegistry registry, ObjectProvider<CapabilityDescriptor> descriptors,
            Environment environment) {
        this.registry = registry;
        this.descriptors = descriptors;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        CapabilityOverrides.apply(descriptors.orderedStream().toList(), environment).forEach(descriptor ->
                Gauge.builder(METRIC, descriptor, d -> "ACTIVE".equalsIgnoreCase(d.status()) ? 1.0 : 0.0)
                        .tag("capability", descriptor.name())
                        .description("1 when the platform capability is active, else 0")
                        .strongReference(true)
                        .register(registry));
    }
}
