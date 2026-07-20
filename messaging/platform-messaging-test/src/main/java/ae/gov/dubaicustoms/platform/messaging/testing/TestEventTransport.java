package ae.gov.dubaicustoms.platform.messaging.testing;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link EventTransport} test double: {@link #send} records the message instead of
 * delivering it anywhere, and {@link #deliver} lets a test simulate an inbound message to whatever
 * is subscribed — the two are independent, so publish-side and handler-side behavior can be
 * asserted separately.
 *
 * <pre>{@code
 * @AutoConfigureTestTransport
 * class OrdersServiceTest {
 *     @Autowired TestEventTransport transport;
 *     @Autowired OrdersService service;
 *
 *     @Test void publishesOrderPlaced() {
 *         service.place("order-1");
 *
 *         assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
 *     }
 * }
 * }</pre>
 *
 * <p>Thread-safe.
 *
 * @since 0.2.0
 */
public final class TestEventTransport implements EventTransport {

    /**
     * One recorded call to {@link #send}.
     *
     * @param destination the destination it was sent to; never {@code null}
     * @param key the partitioning/routing key, or {@code null}
     * @param value the serialized payload; never {@code null}
     * @param headers the headers carried with the message; never {@code null}
     * @since 0.2.0
     */
    public record SentEvent(String destination, byte[] key, byte[] value, Map<String, String> headers) {
    }

    private final CopyOnWriteArrayList<SentEvent> sent = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TransportListener>> listenersByDestination =
            new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "test";
    }

    @Override
    public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        sent.add(new SentEvent(destination, key, value, Map.copyOf(headers)));
    }

    @Override
    public Subscription subscribe(String destination, String group, TransportListener listener) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        var listeners = listenersByDestination.computeIfAbsent(destination, d -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Returns every message recorded by {@link #send} so far, oldest first.
     *
     * @return an immutable snapshot; never {@code null}
     */
    public List<SentEvent> sent() {
        return List.copyOf(sent);
    }

    /**
     * Clears recorded {@link #sent()} history. Subscriptions are unaffected.
     */
    public void clearSent() {
        sent.clear();
    }

    /**
     * Simulates an inbound message: delivers it to every listener currently subscribed to
     * {@code destination}, synchronously on the calling thread.
     *
     * @param destination the destination to deliver to; never {@code null}
     * @param key the partitioning/routing key, or {@code null}
     * @param value the serialized payload; never {@code null}
     * @param headers the headers to carry with the message; never {@code null}
     */
    public void deliver(String destination, byte[] key, byte[] value, Map<String, String> headers) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        listenersByDestination.getOrDefault(destination, new CopyOnWriteArrayList<>())
                .forEach(listener -> listener.onMessage(key, value, headers));
    }

    @Override
    public void close() {
        listenersByDestination.clear();
    }
}
