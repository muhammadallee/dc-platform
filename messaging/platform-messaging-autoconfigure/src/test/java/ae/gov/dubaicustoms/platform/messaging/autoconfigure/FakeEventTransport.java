package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Synchronous, in-JVM test double: delivers to a subscribed listener within the send() call. */
final class FakeEventTransport implements EventTransport {

    record Sent(String destination, byte[] key, byte[] value, Map<String, String> headers) {}

    final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, TransportListener> listenersByDestination = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
        sent.add(new Sent(destination, key, value, headers));
        TransportListener listener = listenersByDestination.get(destination);
        if (listener != null) {
            listener.onMessage(key, value, headers);
        }
    }

    @Override
    public Subscription subscribe(String destination, String group, TransportListener listener) {
        listenersByDestination.put(destination, listener);
        return () -> listenersByDestination.remove(destination);
    }

    @Override
    public void close() {
        listenersByDestination.clear();
    }
}
