package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;

/**
 * {@link MessagingInstrumentation} over Micrometer. Loaded only by the guarded configuration, and resolves its registries
 * on first use, so a registry is never created early. Thread-safe.
 */
public final class MicrometerMessagingInstrumentation implements MessagingInstrumentation {

    private final Supplier<MeterRegistry> meters;
    private final Supplier<ObservationRegistry> observations;

    /**
     * Creates the instrumentation.
     *
     * @param meters supplies the registry, or {@code null} for none; never {@code null}
     * @param observations supplies the registry, or {@code null} for none; never {@code null}
     */
    public MicrometerMessagingInstrumentation(Supplier<MeterRegistry> meters, Supplier<ObservationRegistry> observations) {
        this.meters = meters;
        this.observations = observations;
    }

    @Override
    public void count(String meter, String... tags) {
        MeterRegistry registry = meters.get();
        if (registry != null) {
            registry.counter(meter, tags).increment();
        }
    }

    @Override
    public void observePublish(String destination, String eventType, Runnable publish) {
        ObservationRegistry registry = observations.get();
        Observation.createNotStarted("platform.messaging.publish", registry != null ? registry : ObservationRegistry.NOOP)
                .lowCardinalityKeyValue("destination", destination)
                .lowCardinalityKeyValue("eventType", eventType)
                .observe(publish);
    }
}
