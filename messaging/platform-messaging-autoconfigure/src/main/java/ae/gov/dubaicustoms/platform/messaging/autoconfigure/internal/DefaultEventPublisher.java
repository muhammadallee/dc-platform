package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventHeaders;
import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Default {@link EventPublisher}: wraps the single configured {@link EventTransport}, adding the {@code eventId},
 * {@code eventType}, {@code eventVersion}, {@code producer} and trace headers, under an observation. Refuses a
 * credential-named header. Thread-safe.
 */
public final class DefaultEventPublisher implements EventPublisher {

    private static final String EVENT_TYPE_HEADER = "eventType";
    private static final String EVENT_VERSION_HEADER = "eventVersion";

    private final EventTransport transport;
    private final Supplier<EventSerializer> serializer;
    private final MessagingInstrumentation instrumentation;
    private final MessageTrace trace;
    private final String producer;

    /**
     * Creates the publisher.
     *
     * @param transport sends the message; never {@code null}
     * @param serializer supplies what serializes the payload, resolved at the first publish; never {@code null}
     * @param instrumentation records the publish; never {@code null}
     * @param trace supplies the trace headers; never {@code null}
     * @param producer this service's name; never {@code null}
     */
    public DefaultEventPublisher(EventTransport transport, Supplier<EventSerializer> serializer,
            MessagingInstrumentation instrumentation, MessageTrace trace, String producer) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation must not be null");
        this.trace = Objects.requireNonNull(trace, "trace must not be null");
        this.producer = Objects.requireNonNull(producer, "producer must not be null");
    }

    @Override
    public void publish(String destination, EventEnvelope<?> event) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(event, "event must not be null");
        event.headers().keySet().forEach(name -> {
            if (EventHeaders.isCredentialName(name)) {
                throw new IllegalArgumentException("Events must not hold credentials; refused header: " + name);
            }
        });
        instrumentation.observePublish(destination, event.eventType(), () -> doPublish(destination, event));
    }

    private void doPublish(String destination, EventEnvelope<?> event) {
        Map<String, String> headers = new LinkedHashMap<>(event.headers());
        headers.put(EventEnvelope.EVENT_ID_HEADER, event.eventId());
        headers.put(EVENT_TYPE_HEADER, event.eventType());
        headers.put(EVENT_VERSION_HEADER, String.valueOf(event.eventVersion()));
        headers.put(EventHeaders.PRODUCER, producer);
        headers.putAll(trace.currentHeaders());
        byte[] key = event.key() != null ? event.key().getBytes(StandardCharsets.UTF_8) : null;
        byte[] value = serializer.get().serialize(event.payload());
        try {
            transport.send(destination, key, value, headers);
            instrumentation.count("platform.messaging.published", "destination", destination, "outcome", "success");
        } catch (RuntimeException e) {
            instrumentation.count("platform.messaging.published", "destination", destination, "outcome", "failure");
            throw new EventPublishException("failed to publish to " + destination, e);
        }
    }
}
