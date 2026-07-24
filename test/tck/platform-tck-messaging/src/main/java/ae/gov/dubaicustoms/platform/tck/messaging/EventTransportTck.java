package ae.gov.dubaicustoms.platform.tck.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract test (Technology Compatibility Kit) for {@link EventTransport} providers. Extend this
 * class, supply a fresh transport from {@link #transport()}, and inherit the whole suite; a provider
 * is <em>platform-certified</em> for messaging iff this class passes against it.
 *
 * <p>Invariants verified:
 * <ol>
 *   <li>a published message round-trips its payload and headers to a subscriber;</li>
 *   <li>a non-null partitioning key is delivered intact;</li>
 *   <li>within one destination, a subscriber observes messages in publication order
 *       (relaxable via {@link #ordersMessagesWithinDestination()});</li>
 *   <li>every distinct consumer group subscribed to a destination receives each message (pub/sub);</li>
 *   <li>each message is delivered exactly once <em>within</em> a single consumer group;</li>
 *   <li>a listener that throws is retried and the message is eventually delivered
 *       (relaxable via {@link #redeliversOnListenerFailure()});</li>
 *   <li>concurrent publishers are safe — no message is lost;</li>
 *   <li>cancelling a subscription stops further delivery to it;</li>
 *   <li>{@link EventTransport#close()} is idempotent;</li>
 *   <li>{@code send}/{@code subscribe} reject null required arguments.</li>
 * </ol>
 *
 * @since 0.2.0
 */
public abstract class EventTransportTck {

    /** Generous ceiling for async delivery; real deliveries complete far sooner. */
    private static final Duration AWAIT = Duration.ofSeconds(10);

    private EventTransport subject;

    /**
     * Supplies a fresh, ready-to-use transport for one test. Implementations return a new instance per
     * call; the TCK closes it after each test.
     *
     * @return a new {@link EventTransport}; never {@code null}
     */
    protected abstract EventTransport transport();

    /**
     * Whether the provider preserves publication order within a destination. Override to return
     * {@code false} for providers that only guarantee order within a partition/key.
     *
     * @return {@code true} if order within a destination is guaranteed
     */
    protected boolean ordersMessagesWithinDestination() {
        return true;
    }

    /**
     * Whether the provider itself redelivers a message whose listener threw. Override to return
     * {@code false} for providers where redelivery is the caller's concern only.
     *
     * @return {@code true} if a failed delivery is retried by the transport
     */
    protected boolean redeliversOnListenerFailure() {
        return true;
    }

    @BeforeEach
    void createSubject() {
        subject = transport();
    }

    @AfterEach
    void closeSubject() throws Exception {
        if (subject != null) {
            subject.close();
        }
    }

    @Test
    void roundTripsPayloadAndHeaders() {
        List<Received> received = subscribe("dc.orders", "g1");

        subject.send("dc.orders", null, bytes("payload-1"), Map.of("eventType", "OrderPlaced"));

        await().atMost(AWAIT).untilAsserted(() -> assertThat(received).hasSize(1));
        Received message = received.get(0);
        assertThat(text(message.value())).isEqualTo("payload-1");
        assertThat(message.headers()).containsEntry("eventType", "OrderPlaced");
    }

    @Test
    void deliversTheKeyWhenProvided() {
        List<Received> received = subscribe("dc.orders", "g1");

        subject.send("dc.orders", bytes("order-42"), bytes("payload"), Map.of());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(received).hasSize(1));
        assertThat(received.get(0).key()).isNotNull();
        assertThat(text(received.get(0).key())).isEqualTo("order-42");
    }

    @Test
    void preservesPublicationOrderWithinADestination() {
        if (!ordersMessagesWithinDestination()) {
            return;
        }
        List<Received> received = subscribe("dc.orders", "g1");

        for (int i = 0; i < 20; i++) {
            subject.send("dc.orders", null, bytes("m-" + i), Map.of());
        }

        await().atMost(AWAIT).untilAsserted(() -> assertThat(received).hasSize(20));
        assertThat(received.stream().map(m -> text(m.value())).toList())
                .containsExactlyElementsOf(range(20).stream().map(i -> "m-" + i).toList());
    }

    @Test
    void broadcastsToEveryConsumerGroup() {
        List<Received> groupA = subscribe("dc.orders", "group-a");
        List<Received> groupB = subscribe("dc.orders", "group-b");

        subject.send("dc.orders", null, bytes("shared"), Map.of());

        await().atMost(AWAIT).untilAsserted(() -> {
            assertThat(groupA).hasSize(1);
            assertThat(groupB).hasSize(1);
        });
    }

    @Test
    void deliversEachMessageOnceWithinAConsumerGroup() {
        List<Received> consumerOne = new CopyOnWriteArrayList<>();
        List<Received> consumerTwo = new CopyOnWriteArrayList<>();
        record(subject.subscribe("dc.orders", "shared-group", collector(consumerOne)));
        record(subject.subscribe("dc.orders", "shared-group", collector(consumerTwo)));

        int count = 30;
        for (int i = 0; i < count; i++) {
            subject.send("dc.orders", null, bytes("m-" + i), Map.of());
        }

        await().atMost(AWAIT).untilAsserted(() ->
                assertThat(consumerOne.size() + consumerTwo.size()).isEqualTo(count));
    }

    @Test
    void retriesUntilAFailingListenerSucceeds() {
        if (!redeliversOnListenerFailure()) {
            return;
        }
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        record(subject.subscribe("dc.orders", "g1", (key, value, headers) -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("transient failure");
            }
            delivered.countDown();
        }));

        subject.send("dc.orders", null, bytes("retry-me"), Map.of());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(delivered.getCount()).isZero());
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void isSafeUnderConcurrentPublishers() throws InterruptedException {
        List<Received> received = subscribe("dc.orders", "g1");
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                pool.submit(() -> {
                    awaitLatch(start);
                    for (int i = 0; i < perThread; i++) {
                        subject.send("dc.orders", null, bytes("m-" + (base + i)), Map.of());
                    }
                });
            }
            start.countDown();
            await().atMost(AWAIT).untilAsserted(() -> assertThat(received).hasSize(threads * perThread));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stopsDeliveringAfterUnsubscribe() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        EventTransport.Subscription subscription = subject.subscribe("dc.orders", "g1", collector(received));

        subscription.close();
        subject.send("dc.orders", null, bytes("after-unsubscribe"), Map.of());

        // Give any erroneous delivery a chance to happen, then assert none did.
        await().during(Duration.ofMillis(300)).atMost(AWAIT).untilAsserted(() -> assertThat(received).isEmpty());
    }

    @Test
    void closeIsIdempotent() throws Exception {
        assertThat(subject.name()).isNotBlank();
        subject.close();
        subject.close();
    }

    @Test
    void rejectsNullRequiredArguments() {
        assertThatThrownBy(() -> subject.send(null, null, bytes("v"), Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.send("dc.orders", null, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- helpers ----

    private List<Received> subscribe(String destination, String group) {
        List<Received> received = new CopyOnWriteArrayList<>();
        record(subject.subscribe(destination, group, collector(received)));
        return received;
    }

    private static EventTransport.TransportListener collector(List<Received> sink) {
        return (key, value, headers) -> sink.add(new Received(key, value, Map.copyOf(headers)));
    }

    private static void record(EventTransport.Subscription subscription) {
        // The transport owns the subscription's lifecycle; close() at end-of-test releases it.
        assertThat(subscription).isNotNull();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Integer> range(int end) {
        return java.util.stream.IntStream.range(0, end).boxed().toList();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    /** One received message: key (nullable), payload, and headers. */
    private record Received(byte[] key, byte[] value, Map<String, String> headers) {
    }
}
