package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.EventType;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.MessagingProperties;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The ways a delivery ends on a transport that dead-letters natively, and on one that does not. */
class EventHandlerRegistrarPathsTest {

    private static final String DESTINATION = "dc.orders:placed";

    /** Version 2 of an event. */
    @EventType(value = "order", version = 2)
    public record Order(String ref) {
    }

    private final AtomicInteger attempts = new AtomicInteger();
    private final CountDownLatch firstAttempt = new CountDownLatch(1);
    private final List<String> republished = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();
    private EventTransport.TransportListener listener;

    /** Fails or succeeds on demand. */
    public class Handler {

        private final boolean fails;

        Handler(boolean fails) {
            this.fails = fails;
        }

        @EventHandler(destination = DESTINATION)
        public void on(Order order) {
            attempts.incrementAndGet();
            firstAttempt.countDown();
            if (fails) {
                throw new IllegalStateException("handler failure");
            }
        }
    }

    private EventHandlerRegistrar registrar(boolean nativeDeadLetters, boolean fails, Duration backoff) {
        EventTransport transport = new EventTransport() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
                republished.add(destination);
            }

            @Override
            public Subscription subscribe(String destination, String group, TransportListener each) {
                listener = each;
                return () -> { };
            }

            @Override
            public boolean deadLettersNatively() {
                return nativeDeadLetters;
            }

            @Override
            public void close() {
            }
        };
        var properties = new MessagingProperties(true, "dc.", new MessagingProperties.Handler(
                new MessagingProperties.Retry(3, backoff, backoff)), new MessagingProperties.Dlq(".dlq"), null);
        var trace = new MessageTrace() {
            @Override
            public Map<String, String> currentHeaders() {
                return Map.of();
            }

            @Override
            public Scope continueFrom(Map<String, String> headers) {
                opened.add(headers.get("traceparent"));
                return () -> opened.add("closed");
            }

            @Override
            public Runnable carry(Runnable action) {
                return action;
            }
        };
        var registrar = new EventHandlerRegistrar(() -> transport,
                () -> new JacksonEventSerializer(new ObjectMapper()), () -> properties, "app",
                () -> MessagingInstrumentation.NOOP, () -> trace);
        registrar.postProcessAfterInitialization(new Handler(fails), "handler");
        registrar.start();
        return registrar;
    }

    private void deliver(String json, String version) {
        listener.onMessage(null, json.getBytes(), Map.of("eventType", "order", "eventVersion", version));
    }

    @Test
    void onANativeTransportAnExhaustedMessageThrowsAndIsNotRepublished() {
        registrar(true, true, Duration.ofMillis(1));

        assertThatThrownBy(() -> deliver("{\"ref\":\"a\"}", "2"))
                .isInstanceOf(EventTransport.TransportListener.DeadLetterException.class).hasNoCause();

        assertThat(attempts).hasValue(3);
        assertThat(republished).isEmpty();
    }

    @Test
    void onAnotherTransportAnExhaustedMessageIsRepublished() {
        registrar(false, true, Duration.ofMillis(1));

        deliver("{\"ref\":\"a\"}", "2");

        assertThat(attempts).hasValue(3);
        assertThat(republished).containsExactly(DESTINATION + ".dlq");
    }

    @Test
    void anUnreadablePayloadIsDeadLetteredWithoutAnAttempt() {
        registrar(true, false, Duration.ofMillis(1));

        assertThatThrownBy(() -> deliver("not json", "2"))
                .isInstanceOf(EventTransport.TransportListener.DeadLetterException.class).hasMessage("unreadable message");

        assertThat(attempts).hasValue(0);
    }

    @Test
    void anUnknownFieldIsTolerated() {
        registrar(true, false, Duration.ofMillis(1));

        deliver("{\"ref\":\"a\",\"addedLater\":1}", "2");

        assertThat(attempts).hasValue(1);
    }

    @Test
    void aNewerVersionIsDeadLetteredWithoutAnAttemptAndAnOlderOneIsDelivered() {
        registrar(true, false, Duration.ofMillis(1));

        assertThatThrownBy(() -> deliver("{\"ref\":\"a\"}", "3")).hasMessage("event version too new");
        assertThat(attempts).hasValue(0);

        deliver("{\"ref\":\"a\"}", "1");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void aStopDuringABackoffPutsTheMessageBackAtOnce() throws Exception {
        var registrar = registrar(true, true, Duration.ofMinutes(5));
        var delivery = CompletableFuture.runAsync(() -> deliver("{\"ref\":\"a\"}", "2"));
        assertThat(firstAttempt.await(10, TimeUnit.SECONDS)).isTrue();

        registrar.stop();

        assertThatThrownBy(() -> delivery.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(EventTransport.TransportListener.RequeueException.class);
        assertThat(attempts).hasValue(1);
        assertThat(registrar.isRunning()).isFalse();
    }

    @Test
    void anInterruptDuringABackoffPutsTheMessageBack() throws Exception {
        registrar(true, true, Duration.ofMinutes(5));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> deliver("{\"ref\":\"a\"}", "2"))
                    .isInstanceOf(EventTransport.TransportListener.RequeueException.class);
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void theHandlerRunsInASpanContinuingTheMessage() {
        registrar(true, false, Duration.ofMillis(1));

        listener.onMessage(null, "{\"ref\":\"a\"}".getBytes(), Map.of("eventType", "order", "eventVersion", "2",
                "traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));

        assertThat(opened).containsExactly("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "closed");
    }

    @Test
    void aVersionThatIsNotANumberIsDeadLetteredNotTreatedAsOne() {
        registrar(true, false, Duration.ofMillis(1));

        assertThatThrownBy(() -> deliver("{\"ref\":\"a\"}", "2147483648")).hasMessage("unreadable event version");

        assertThat(attempts).hasValue(0);
    }

    @Test
    void itRestartsAfterAStop() throws Exception {
        var registrar = registrar(true, false, Duration.ofMillis(1));
        registrar.start();
        assertThat(registrar.isRunning()).isTrue();

        registrar.stop();
        listener = null;
        assertThat(registrar.isRunning()).isFalse();

        registrar.start();
        assertThat(registrar.isRunning()).isTrue();
        assertThat(listener).as("subscribed again").isNotNull();
    }

    /** Two filtered handlers on one destination. */
    public class Routed {

        @EventHandler(destination = DESTINATION, eventType = "order")
        public void onOrder(String payload) {
            attempts.incrementAndGet();
        }

        @EventHandler(destination = DESTINATION, eventType = "refund")
        public void onRefund(String payload) {
            attempts.addAndGet(100);
        }
    }

    /** One filtered handler, alone on its destination. */
    public class FilteredOnly {

        @EventHandler(destination = "dc.filtered", eventType = "order")
        public void on(String payload) {
            attempts.incrementAndGet();
        }
    }

    /** Two handlers on a destination nobody has subscribed to yet. */
    public class LateTwo {

        @EventHandler(destination = "dc.late", eventType = "a")
        public void onA(String payload) {
            attempts.incrementAndGet();
        }

        @EventHandler(destination = "dc.late", eventType = "b")
        public void onB(String payload) {
            attempts.addAndGet(100);
        }
    }

    @Test
    void aLateBeanIsFullyScannedBeforeItsConsumerStarts() {
        var registrar = registrar(true, false, Duration.ofMillis(1));
        var subscribedBefore = listener;

        registrar.postProcessAfterInitialization(new LateTwo(), "late");
        assertThat(listener).isNotSameAs(subscribedBefore);

        // Whichever method was scanned first, the one consumer knows both.
        listener.onMessage(null, "\"a\"".getBytes(), Map.of("eventType", "b", "eventVersion", "1"));
        assertThat(attempts).hasValue(100);
    }

    @Test
    void aSecondInstanceOfTheSameBeanIsNotASecondHandler() {
        var registrar = registrar(true, false, Duration.ofMillis(1));

        registrar.postProcessAfterInitialization(new Handler(false), "again");

        deliver("{\"ref\":\"a\"}", "2");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void consumersStartBeforeTheOutboxDrainerAndStopAfterIt() {
        var registrar = registrar(true, false, Duration.ofMillis(1));

        assertThat(registrar.getPhase()).isLessThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 4096);
    }

    @Test
    void aFailureAfterARestartReadsTheOldSubscriptionsStop() throws Exception {
        var registrar = registrar(true, true, Duration.ofMinutes(5));
        var old = listener;
        registrar.stop();
        registrar.start();

        assertThatThrownBy(() -> old.onMessage(null, "{\"ref\":\"a\"}".getBytes(), Map.of("eventType", "order", "eventVersion", "2")))
                .isInstanceOf(EventTransport.TransportListener.RequeueException.class);
    }

    /** Two handlers that could receive the same message. */
    public class Clashing {

        @EventHandler(destination = "dc.clash")
        public void first(String payload) {
        }

        @EventHandler(destination = "dc.clash")
        public void second(String payload) {
        }
    }

    @Test
    void handlersOnOneDestinationShareASubscriptionAndAreRoutedByType() {
        var registrar = registrar(true, false, Duration.ofMillis(1));
        registrar.postProcessAfterInitialization(new Routed(), "routed");

        listener.onMessage(null, "\"a\"".getBytes(), Map.of("eventType", "refund", "eventVersion", "1"));
        assertThat(attempts).hasValue(100);
    }

    @Test
    void aMessageNoHandlerWantsIsAcknowledged() {
        var registrar = registrar(true, false, Duration.ofMillis(1));
        registrar.postProcessAfterInitialization(new FilteredOnly(), "filtered");

        listener.onMessage(null, "\"a\"".getBytes(), Map.of("eventType", "other", "eventVersion", "1"));

        assertThat(attempts).hasValue(0);
        assertThat(registrar.isRunning()).isTrue();
    }

    @Test
    void twoHandlersForTheSameMessageFailStartup() {
        var registrar = registrar(true, false, Duration.ofMillis(1));

        assertThatThrownBy(() -> registrar.postProcessAfterInitialization(new Clashing(), "clashing"))
                .hasMessageContaining("both handle");
    }
}
