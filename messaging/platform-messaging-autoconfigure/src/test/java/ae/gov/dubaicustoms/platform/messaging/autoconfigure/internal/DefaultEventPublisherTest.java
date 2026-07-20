package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.MessagingProperties;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultEventPublisherTest {

    record OrderPlaced(String orderId) {}

    private final EventSerializer serializer = new EventSerializer() {
        @Override
        public byte[] serialize(Object payload) {
            return payload.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T deserialize(byte[] bytes, Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    };

    private final MessagingProperties properties =
            new MessagingProperties(true, "dc.", null, null, new MessagingProperties.Correlation(true), null);

    @Test
    void addsEventTypeAndVersionHeaders() {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        EventTransport transport = fakeTransport((destination, key, value, headers) -> capturedHeaders.set(headers));
        var publisher = new DefaultEventPublisher(transport, serializer, properties, ObservationRegistry.NOOP, null);

        publisher.publish("dc.orders", EventEnvelope.of(new OrderPlaced("o-1")).build());

        assertThat(capturedHeaders.get()).containsEntry("eventType", "OrderPlaced").containsEntry("eventVersion", "1");
    }

    @Test
    void propagatesCorrelationIdWhenContextIsOpen() throws Exception {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        EventTransport transport = fakeTransport((destination, key, value, headers) -> capturedHeaders.set(headers));
        var publisher = new DefaultEventPublisher(transport, serializer, properties, ObservationRegistry.NOOP, null);

        String correlationId = "0123456789abcdef0123456789abcdef";
        try (var scope = RequestContext.open(new CorrelationId(correlationId.substring(0, 32)), Map.of())) {
            publisher.publish("dc.orders", EventEnvelope.of(new OrderPlaced("o-1")).build());
        }

        assertThat(capturedHeaders.get()).containsEntry("correlationId", correlationId.substring(0, 32));
    }

    @Test
    void doesNotPropagateCorrelationIdWhenDisabled() {
        var propagationDisabled =
                new MessagingProperties(true, "dc.", null, null, new MessagingProperties.Correlation(false), null);
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        EventTransport transport = fakeTransport((destination, key, value, headers) -> capturedHeaders.set(headers));
        var publisher =
                new DefaultEventPublisher(transport, serializer, propagationDisabled, ObservationRegistry.NOOP, null);

        publisher.publish("dc.orders", EventEnvelope.of(new OrderPlaced("o-1")).build());

        assertThat(capturedHeaders.get()).doesNotContainKey("correlationId");
    }

    @Test
    void wrapsTransportFailureInEventPublishException() {
        EventTransport transport = new EventTransport() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
                throw new IllegalStateException("boom");
            }

            @Override
            public Subscription subscribe(String destination, String group, TransportListener listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        var publisher = new DefaultEventPublisher(transport, serializer, properties, ObservationRegistry.NOOP, null);

        assertThatThrownBy(() -> publisher.publish("dc.orders", EventEnvelope.of(new OrderPlaced("o-1")).build()))
                .isInstanceOf(EventPublishException.class)
                .extracting(e -> ((EventPublishException) e).code().value())
                .isEqualTo("DC-MSG-0001");
    }

    @FunctionalInterface
    private interface SendListener {
        void onSend(String destination, byte[] key, byte[] value, Map<String, String> headers);
    }

    private static EventTransport fakeTransport(SendListener onSend) {
        return new EventTransport() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
                onSend.onSend(destination, key, value, headers);
            }

            @Override
            public Subscription subscribe(String destination, String group, TransportListener listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
    }
}
