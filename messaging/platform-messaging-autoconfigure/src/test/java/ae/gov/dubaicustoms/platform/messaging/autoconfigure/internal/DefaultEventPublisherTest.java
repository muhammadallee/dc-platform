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

    @Test
    void addsEventTypeAndVersionHeaders() {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        EventTransport transport = fakeTransport((destination, key, value, headers) -> capturedHeaders.set(headers));
        var publisher = new DefaultEventPublisher(transport, serializer, MessagingInstrumentation.NOOP, MessageTrace.NONE, "app");

        EventEnvelope<OrderPlaced> event = EventEnvelope.of(new OrderPlaced("o-1")).build();
        publisher.publish("dc.orders", event);

        assertThat(capturedHeaders.get()).containsEntry("eventType", "OrderPlaced").containsEntry("eventVersion", "1")
                .containsEntry("eventId", event.eventId());
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
        var publisher = new DefaultEventPublisher(transport, serializer, MessagingInstrumentation.NOOP, MessageTrace.NONE, "app");

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

    @Test
    void addsTheProducerAndTheTraceHeaders() {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        EventTransport transport = fakeTransport((destination, key, value, headers) -> capturedHeaders.set(headers));
        var trace = new MessageTrace() {
            @Override
            public Map<String, String> currentHeaders() {
                return Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
            }

            @Override
            public Scope continueFrom(Map<String, String> headers) {
                return () -> { };
            }

            @Override
            public Runnable carry(Runnable action) {
                return action;
            }
        };
        var publisher = new DefaultEventPublisher(transport, serializer, MessagingInstrumentation.NOOP, trace, "orders");

        publisher.publish("dc.orders", EventEnvelope.of(new OrderPlaced("o-1")).build());

        assertThat(capturedHeaders.get()).containsEntry("producer", "orders")
                .containsEntry("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                .doesNotContainKey("correlationId");
    }

    @Test
    void refusesACredentialNamedHeaderAndSendsNothing() {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        EventTransport transport = fakeTransport((destination, key, value, headers) -> capturedHeaders.set(headers));
        var publisher = new DefaultEventPublisher(transport, serializer, MessagingInstrumentation.NOOP, MessageTrace.NONE, "app");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> publisher.publish("dc.orders",
                EventEnvelope.of(new OrderPlaced("o-1")).header("x-auth-token", "value").build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("x-auth-token")
                .hasMessageNotContaining("value");

        assertThat(capturedHeaders.get()).isNull();
    }
}
