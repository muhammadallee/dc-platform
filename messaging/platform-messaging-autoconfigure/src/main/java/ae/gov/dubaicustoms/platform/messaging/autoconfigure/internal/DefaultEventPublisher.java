package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.MessagingProperties;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link EventPublisher}: wraps the single configured {@link EventTransport}, adding
 * {@code correlationId}/{@code eventType}/{@code eventVersion} headers and instrumenting the send
 * with a Micrometer {@link Observation}.
 */
public final class DefaultEventPublisher implements EventPublisher {

    private static final String EVENT_TYPE_HEADER = "eventType";
    private static final String EVENT_VERSION_HEADER = "eventVersion";
    private static final String CORRELATION_ID_HEADER = "correlationId";

    private final EventTransport transport;
    private final EventSerializer serializer;
    private final MessagingProperties properties;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public DefaultEventPublisher(EventTransport transport, EventSerializer serializer, MessagingProperties properties,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(String destination, EventEnvelope<?> event) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Observation observation = Observation.createNotStarted("platform.messaging.publish", observationRegistry)
                .lowCardinalityKeyValue("destination", destination)
                .lowCardinalityKeyValue("eventType", event.eventType());
        observation.observe(() -> doPublish(destination, event));
    }

    private void doPublish(String destination, EventEnvelope<?> event) {
        Map<String, String> headers = new LinkedHashMap<>(event.headers());
        headers.put(EVENT_TYPE_HEADER, event.eventType());
        headers.put(EVENT_VERSION_HEADER, String.valueOf(event.eventVersion()));
        if (properties.correlation().propagate()) {
            RequestContext.correlationId().ifPresent(id -> headers.put(CORRELATION_ID_HEADER, id.value()));
        }
        byte[] key = event.key() != null ? event.key().getBytes(StandardCharsets.UTF_8) : null;
        byte[] value = serializer.serialize(event.payload());
        try {
            transport.send(destination, key, value, headers);
            count(destination, "success");
        } catch (RuntimeException e) {
            count(destination, "failure");
            throw new EventPublishException("failed to publish to " + destination, e);
        }
    }

    private void count(String destination, String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("platform.messaging.published", "destination", destination, "outcome", outcome)
                    .increment();
        }
    }
}
