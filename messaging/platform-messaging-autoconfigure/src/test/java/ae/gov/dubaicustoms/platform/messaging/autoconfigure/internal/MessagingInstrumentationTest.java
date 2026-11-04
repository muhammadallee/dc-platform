package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MessagingInstrumentationTest {

    @Test
    void countsAndObservesOverMicrometer() {
        var registry = new SimpleMeterRegistry();
        var instrumentation = new MicrometerMessagingInstrumentation(() -> registry, () -> ObservationRegistry.create());
        var ran = new AtomicBoolean();

        instrumentation.count("meter", "outcome", "dlq");
        instrumentation.observePublish("dc.orders", "order", () -> ran.set(true));

        assertThat(registry.counter("meter", "outcome", "dlq").count()).isEqualTo(1);
        assertThat(ran).isTrue();
    }

    @Test
    void withoutRegistriesItStillRuns() {
        var instrumentation = new MicrometerMessagingInstrumentation(() -> null, () -> null);
        var ran = new AtomicBoolean();

        instrumentation.count("meter");
        instrumentation.observePublish("dc.orders", "order", () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void theNoOpsRunWhatTheyAreGiven() {
        var ran = new AtomicBoolean();
        MessagingInstrumentation.NOOP.count("meter");
        MessagingInstrumentation.NOOP.observePublish("dc.orders", "order", () -> ran.set(true));
        assertThat(ran).isTrue();

        assertThat(MessageTrace.NONE.currentHeaders()).isEmpty();
        MessageTrace.NONE.continueFrom(Map.of()).close();
        Runnable action = () -> { };
        assertThat(MessageTrace.NONE.carry(action)).isSameAs(action);
    }

    @Test
    void withoutATracerTheTraceSupportDoesNothing() {
        var trace = new TracingMessageTrace(() -> null, () -> null);

        assertThat(trace.currentHeaders()).isEmpty();
        trace.continueFrom(Map.of()).close();
        Runnable action = () -> { };
        assertThat(trace.carry(action)).isSameAs(action);
    }

    @Test
    void aCarriedActionStillRuns() {
        var trace = new TracingMessageTrace(() -> io.micrometer.tracing.Tracer.NOOP, () -> null);
        var ran = new AtomicBoolean();

        assertThat(trace.currentHeaders()).isEmpty();
        trace.carry(() -> ran.set(true)).run();

        assertThat(ran).isTrue();
    }
}
