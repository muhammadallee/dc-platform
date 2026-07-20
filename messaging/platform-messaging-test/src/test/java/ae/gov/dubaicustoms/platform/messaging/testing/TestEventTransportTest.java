package ae.gov.dubaicustoms.platform.messaging.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TestEventTransportTest {

    private final TestEventTransport transport = new TestEventTransport();

    @Test
    void nameIsTest() {
        assertThat(transport.name()).isEqualTo("test");
    }

    @Test
    void sendRecordsTheMessageWithoutDeliveringIt() {
        List<String> received = new CopyOnWriteArrayList<>();
        transport.subscribe("dc.orders", "group-a", (key, value, headers) -> received.add(new String(value)));

        transport.send("dc.orders", null, "hello".getBytes(), Map.of("eventType", "OrderPlaced"));

        assertThat(transport.sent()).hasSize(1);
        assertThat(transport.sent().get(0).destination()).isEqualTo("dc.orders");
        assertThat(received).isEmpty();
    }

    @Test
    void clearSentRemovesRecordedHistory() {
        transport.send("dc.orders", null, "hello".getBytes(), Map.of());

        transport.clearSent();

        assertThat(transport.sent()).isEmpty();
    }

    @Test
    void deliverInvokesSubscribedListenersWithoutRecordingAsSent() {
        List<String> received = new CopyOnWriteArrayList<>();
        transport.subscribe("dc.orders", "group-a", (key, value, headers) -> received.add(new String(value)));

        transport.deliver("dc.orders", null, "hello".getBytes(), Map.of("eventType", "OrderPlaced"));

        assertThat(received).containsExactly("hello");
        assertThat(transport.sent()).isEmpty();
    }

    @Test
    void deliverIsANoOpWhenNoListenerIsSubscribed() {
        transport.deliver("dc.nobody-listening", null, "hello".getBytes(), Map.of());
    }

    @Test
    void closeRemovesAllSubscriptions() {
        List<String> received = new CopyOnWriteArrayList<>();
        transport.subscribe("dc.orders", "group-a", (key, value, headers) -> received.add(new String(value)));

        transport.close();
        transport.deliver("dc.orders", null, "hello".getBytes(), Map.of());

        assertThat(received).isEmpty();
    }

    @Test
    void subscriptionCloseStopsDelivery() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        var subscription = transport.subscribe("dc.orders", "group-a", (key, value, headers) -> received.add(new String(value)));

        subscription.close();
        transport.deliver("dc.orders", null, "hello".getBytes(), Map.of());

        assertThat(received).isEmpty();
    }
}
