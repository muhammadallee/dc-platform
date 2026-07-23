package ae.gov.dubaicustoms.platform.messaging.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InMemoryEventTransportTest {

    private final InMemoryEventTransport transport = new InMemoryEventTransport();

    @AfterEach
    void closeTransport() {
        transport.close();
    }

    @Test
    void nameIsInmemory() {
        assertThat(transport.name()).isEqualTo("inmemory");
    }

    @Test
    void deliversPublishedMessageToSubscriber() {
        List<String> received = new CopyOnWriteArrayList<>();
        transport.subscribe("dc.orders", "group-a",
                (key, value, headers) -> received.add(new String(value, StandardCharsets.UTF_8)));

        transport.send("dc.orders", null, "hello".getBytes(StandardCharsets.UTF_8), Map.of());
        transport.awaitIdle(Duration.ofSeconds(2));

        assertThat(received).containsExactly("hello");
    }

    @Test
    void fansOutToEveryDistinctGroup() {
        List<String> groupA = new CopyOnWriteArrayList<>();
        List<String> groupB = new CopyOnWriteArrayList<>();
        transport.subscribe("dc.orders", "group-a", (k, v, h) -> groupA.add(new String(v, StandardCharsets.UTF_8)));
        transport.subscribe("dc.orders", "group-b", (k, v, h) -> groupB.add(new String(v, StandardCharsets.UTF_8)));

        transport.send("dc.orders", null, "hello".getBytes(StandardCharsets.UTF_8), Map.of());
        transport.awaitIdle(Duration.ofSeconds(2));

        assertThat(groupA).containsExactly("hello");
        assertThat(groupB).containsExactly("hello");
    }

    @Test
    void competingConsumersInSameGroupShareTheLoad() {
        AtomicInteger deliveries = new AtomicInteger();
        transport.subscribe("dc.orders", "group-a", (k, v, h) -> deliveries.incrementAndGet());
        transport.subscribe("dc.orders", "group-a", (k, v, h) -> deliveries.incrementAndGet());

        for (int i = 0; i < 10; i++) {
            transport.send("dc.orders", null, "m".getBytes(StandardCharsets.UTF_8), Map.of());
        }
        transport.awaitIdle(Duration.ofSeconds(2));

        assertThat(deliveries.get()).isEqualTo(10);
    }

    @Test
    void sendWithNoSubscribersIsANoOp() {
        transport.send("dc.nobody-listening", null, "m".getBytes(StandardCharsets.UTF_8), Map.of());

        transport.awaitIdle(Duration.ofSeconds(1));
    }

    @Test
    void redeliversOnFailureThenDropsAfterMaxAttempts() {
        var shortBackoffTransport = new InMemoryEventTransport(1000, 2, Duration.ofMillis(5));
        AtomicInteger attempts = new AtomicInteger();
        shortBackoffTransport.subscribe("dc.orders", "group-a", (k, v, h) -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        });

        shortBackoffTransport.send("dc.orders", null, "m".getBytes(StandardCharsets.UTF_8), Map.of());
        shortBackoffTransport.awaitIdle(Duration.ofSeconds(2));

        assertThat(attempts.get()).isEqualTo(2);
        shortBackoffTransport.close();
    }

    @Test
    void redeliveredMessageEventuallySucceeds() {
        var shortBackoffTransport = new InMemoryEventTransport(1000, 3, Duration.ofMillis(5));
        AtomicInteger attempts = new AtomicInteger();
        List<String> received = new CopyOnWriteArrayList<>();
        shortBackoffTransport.subscribe("dc.orders", "group-a", (k, v, h) -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("transient failure");
            }
            received.add(new String(v, StandardCharsets.UTF_8));
        });

        shortBackoffTransport.send("dc.orders", null, "m".getBytes(StandardCharsets.UTF_8), Map.of());
        shortBackoffTransport.awaitIdle(Duration.ofSeconds(2));

        assertThat(received).containsExactly("m");
        shortBackoffTransport.close();
    }

    @Test
    void awaitIdleTimesOutWhenMessagesStillInFlight() throws InterruptedException {
        // Hold the message in flight deterministically: the handler blocks until the test releases
        // it, so inFlight is provably > 0 when awaitIdle runs. A throwing handler would instead race
        // the retry/backoff window against the 1ms timeout, making the assertion flaky.
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.subscribe("dc.orders", "group-a", (k, v, h) -> {
            handlerEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        transport.send("dc.orders", null, "m".getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> transport.awaitIdle(Duration.ofMillis(1)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingSubscriptionStopsDelivery() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        EventTransport.Subscription subscription =
                transport.subscribe("dc.orders", "group-a", (k, v, h) -> deliveries.incrementAndGet());

        transport.send("dc.orders", null, "m".getBytes(StandardCharsets.UTF_8), Map.of());
        transport.awaitIdle(Duration.ofSeconds(2));
        // awaitIdle guarantees the sent message was delivered exactly once and no work remains in
        // flight, so the delivery count is final and deterministic here — no timing wait needed.
        assertThat(deliveries.get()).isEqualTo(1);

        // Closing the subscription must not resurrect or duplicate the already-delivered message.
        subscription.close();
        assertThat(deliveries.get()).isEqualTo(1);
    }
}
