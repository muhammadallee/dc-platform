package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.MessagingProperties;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventHandlerRegistrarTest {

    /** Synchronous fake: send() invokes the subscribed listener directly, one per destination. */
    private static final class SyncTransport implements EventTransport {
        final List<Object[]> sent = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, TransportListener> listeners = new ConcurrentHashMap<>();

        @Override
        public String name() {
            return "sync";
        }

        @Override
        public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
            sent.add(new Object[] {destination, value, headers});
            TransportListener listener = listeners.get(destination);
            if (listener != null) {
                listener.onMessage(key, value, headers);
            }
        }

        @Override
        public Subscription subscribe(String destination, String group, TransportListener listener) {
            listeners.put(destination, listener);
            return () -> listeners.remove(destination);
        }

        @Override
        public void close() {
        }
    }

    private static final EventSerializer PLAIN_TEXT = new EventSerializer() {
        @Override
        public byte[] serialize(Object payload) {
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] bytes, Class<T> type) {
            return (T) new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    };

    private static MessagingProperties properties(int maxAttempts) {
        return new MessagingProperties(true, "dc.",
                new MessagingProperties.Handler(new MessagingProperties.Retry(maxAttempts, Duration.ofMillis(1))),
                new MessagingProperties.Dlq(".dlq"), new MessagingProperties.Correlation(true), null);
    }

    static final class PayloadHandler {
        final List<String> received = new CopyOnWriteArrayList<>();

        @EventHandler(destination = "dc.orders")
        void onOrder(String payload) {
            received.add(payload);
        }
    }

    @Test
    void deliversDeserializedPayloadToHandlerMethod() {
        var transport = new SyncTransport();
        var registrar = new EventHandlerRegistrar(transport, PLAIN_TEXT, properties(3), "app", null);
        var handler = new PayloadHandler();

        registrar.postProcessAfterInitialization(handler, "handler");
        transport.send("dc.orders", null, "hello".getBytes(StandardCharsets.UTF_8), Map.of("eventType", "Order"));

        assertThat(handler.received).containsExactly("hello");
    }

    static final class EnvelopeHandler {
        final List<EventEnvelope<String>> received = new CopyOnWriteArrayList<>();

        @EventHandler(destination = "dc.orders")
        @SuppressWarnings("unchecked")
        void onOrder(EventEnvelope<String> envelope) {
            received.add(envelope);
        }
    }

    @Test
    void deliversEnvelopeWhenHandlerWantsMetadata() {
        var transport = new SyncTransport();
        var registrar = new EventHandlerRegistrar(transport, PLAIN_TEXT, properties(3), "app", null);
        var handler = new EnvelopeHandler();

        registrar.postProcessAfterInitialization(handler, "handler");
        transport.send("dc.orders", null, "hello".getBytes(StandardCharsets.UTF_8),
                Map.of("eventType", "Order", "eventVersion", "2"));

        assertThat(handler.received).hasSize(1);
        assertThat(handler.received.get(0).payload()).isEqualTo("hello");
        assertThat(handler.received.get(0).eventVersion()).isEqualTo(2);
    }

    static final class FilteredHandler {
        final AtomicInteger invocations = new AtomicInteger();

        @EventHandler(destination = "dc.orders", eventType = "OrderPlaced")
        void onOrderPlaced(String payload) {
            invocations.incrementAndGet();
        }
    }

    @Test
    void skipsDeliveryWhenEventTypeFilterDoesNotMatch() {
        var transport = new SyncTransport();
        var registrar = new EventHandlerRegistrar(transport, PLAIN_TEXT, properties(3), "app", null);
        var handler = new FilteredHandler();

        registrar.postProcessAfterInitialization(handler, "handler");
        transport.send("dc.orders", null, "x".getBytes(StandardCharsets.UTF_8), Map.of("eventType", "OrderCancelled"));

        assertThat(handler.invocations.get()).isZero();
    }

    static final class AlwaysFailingHandler {
        final AtomicInteger attempts = new AtomicInteger();

        @EventHandler(destination = "dc.orders")
        void onOrder(String payload) {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        }
    }

    @Test
    void retriesThenRepublishesToDlqAfterExhaustingAttempts() {
        var transport = new SyncTransport();
        var registrar = new EventHandlerRegistrar(transport, PLAIN_TEXT, properties(2), "app", null);
        var handler = new AlwaysFailingHandler();

        registrar.postProcessAfterInitialization(handler, "handler");
        transport.send("dc.orders", null, "x".getBytes(StandardCharsets.UTF_8), Map.of("eventType", "Order"));

        assertThat(handler.attempts.get()).isEqualTo(2);
        assertThat(transport.sent).anySatisfy(entry -> assertThat(entry[0]).isEqualTo("dc.orders.dlq"));
    }

    static final class EventuallySucceedsHandler {
        final AtomicInteger attempts = new AtomicInteger();

        @EventHandler(destination = "dc.orders")
        void onOrder(String payload) {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("transient");
            }
        }
    }

    @Test
    void succeedsOnRetryWithoutReachingDlq() {
        var transport = new SyncTransport();
        var registrar = new EventHandlerRegistrar(transport, PLAIN_TEXT, properties(3), "app", null);
        var handler = new EventuallySucceedsHandler();

        registrar.postProcessAfterInitialization(handler, "handler");
        transport.send("dc.orders", null, "x".getBytes(StandardCharsets.UTF_8), Map.of("eventType", "Order"));

        assertThat(handler.attempts.get()).isEqualTo(2);
        assertThat(transport.sent).noneSatisfy(entry -> assertThat(entry[0]).isEqualTo("dc.orders.dlq"));
    }

    static final class BadSignatureHandler {
        @EventHandler(destination = "dc.orders")
        void onOrder(String a, String b) {
        }
    }

    @Test
    void rejectsHandlerMethodWithWrongParameterCount() {
        var transport = new SyncTransport();
        var registrar = new EventHandlerRegistrar(transport, PLAIN_TEXT, properties(3), "app", null);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> registrar.postProcessAfterInitialization(new BadSignatureHandler(), "handler"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void destroyClosesAllSubscriptions() {
        var transport = new SyncTransport();
        var registrar = new EventHandlerRegistrar(transport, PLAIN_TEXT, properties(3), "app", null);
        registrar.postProcessAfterInitialization(new PayloadHandler(), "handler");

        registrar.destroy();

        transport.send("dc.orders", null, "x".getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(transport.sent).hasSize(1); // send() recorded, but no listener left to deliver to
    }
}
