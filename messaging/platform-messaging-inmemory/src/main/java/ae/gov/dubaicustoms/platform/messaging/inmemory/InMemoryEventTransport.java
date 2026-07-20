package ae.gov.dubaicustoms.platform.messaging.inmemory;

import ae.gov.dubaicustoms.platform.messaging.inmemory.internal.QueuedMessage;
import ae.gov.dubaicustoms.platform.messaging.inmemory.internal.SubscriptionDispatcher;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The local + test messaging transport: bounded in-JVM queues per (destination, group), one
 * dispatcher virtual thread per subscription. No broker, no network — this is why it's the
 * default in golden-path examples, CI, and the messaging TCK (phase 12) reference implementation.
 *
 * <p>Fan-out model: {@link #send} delivers to every distinct consumer group subscribed to a
 * destination (pub/sub across groups); within one group, multiple {@link #subscribe} calls share
 * the same bounded queue, so they compete for messages (load-balanced, like a Kafka consumer
 * group). Delivery failures are retried a bounded number of times with a fixed backoff, then
 * dropped and logged — see {@link SubscriptionDispatcher} for the exact policy; this is a
 * reference-implementation choice for local/dev/test, not a delivery guarantee to build production
 * exactly-once semantics on.
 *
 * <p>{@link #awaitIdle(Duration)} blocks until every enqueued message has been delivered or
 * dropped, for deterministic (sleep-free) tests: publish, then {@code awaitIdle}, then assert.
 *
 * <p>Thread-safe.
 *
 * @since 0.2.0
 */
public final class InMemoryEventTransport implements EventTransport {

    private static final int DEFAULT_QUEUE_CAPACITY = 1000;
    private static final int DEFAULT_MAX_REDELIVERY_ATTEMPTS = 3;
    private static final Duration DEFAULT_REDELIVERY_BACKOFF = Duration.ofMillis(50);
    private static final Duration IDLE_POLL_INTERVAL = Duration.ofMillis(10);

    private final int queueCapacity;
    private final int maxRedeliveryAttempts;
    private final Duration redeliveryBackoff;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, BlockingQueue<QueuedMessage>>> queuesByDestination =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SubscriptionDispatcher> dispatchers = new CopyOnWriteArrayList<>();
    private final AtomicLong inFlight = new AtomicLong();

    /** Creates a transport with sane defaults: 1000-message bounded queues, 3 redelivery attempts, 50ms backoff. */
    public InMemoryEventTransport() {
        this(DEFAULT_QUEUE_CAPACITY, DEFAULT_MAX_REDELIVERY_ATTEMPTS, DEFAULT_REDELIVERY_BACKOFF);
    }

    /**
     * Creates a transport with explicit tuning.
     *
     * @param queueCapacity bounded capacity of each per-(destination, group) queue; {@link #send}
     *     blocks (applying backpressure) once a queue is full
     * @param maxRedeliveryAttempts total delivery attempts per message before it is dropped+logged;
     *     at least 1
     * @param redeliveryBackoff pause before each redelivery attempt; never {@code null}
     */
    public InMemoryEventTransport(int queueCapacity, int maxRedeliveryAttempts, Duration redeliveryBackoff) {
        this.queueCapacity = queueCapacity;
        this.maxRedeliveryAttempts = maxRedeliveryAttempts;
        this.redeliveryBackoff = Objects.requireNonNull(redeliveryBackoff, "redeliveryBackoff must not be null");
    }

    @Override
    public String name() {
        return "inmemory";
    }

    @Override
    public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        var groups = queuesByDestination.get(destination);
        if (groups == null || groups.isEmpty()) {
            // No subscribers yet: at-least-once only binds subscribers that exist at send time,
            // same as a broker topic nobody has consumed from yet.
            return;
        }
        QueuedMessage message = new QueuedMessage(key, value, Map.copyOf(headers), 0);
        for (BlockingQueue<QueuedMessage> queue : groups.values()) {
            inFlight.incrementAndGet();
            try {
                // put(), not offer(): a full bounded queue applies backpressure to the publisher
                // rather than silently dropping a message the caller believes was delivered.
                queue.put(message);
            } catch (InterruptedException e) {
                inFlight.decrementAndGet();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while publishing to " + destination, e);
            }
        }
    }

    @Override
    public Subscription subscribe(String destination, String group, TransportListener listener) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        BlockingQueue<QueuedMessage> queue = queuesByDestination
                .computeIfAbsent(destination, d -> new ConcurrentHashMap<>())
                .computeIfAbsent(group, g -> new LinkedBlockingQueue<>(queueCapacity));
        var dispatcher = new SubscriptionDispatcher(
                queue, listener, destination, group, maxRedeliveryAttempts, redeliveryBackoff, inFlight);
        dispatchers.add(dispatcher);
        return () -> {
            dispatchers.remove(dispatcher);
            dispatcher.close();
        };
    }

    /**
     * Blocks until every message sent so far has been delivered (or dropped after exhausting
     * redelivery), for deterministic tests.
     *
     * @param timeout how long to wait before giving up; never {@code null}
     * @throws IllegalStateException if messages are still in flight when {@code timeout} elapses
     */
    public void awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "still " + inFlight.get() + " message(s) in flight after " + timeout);
            }
            try {
                Thread.sleep(IDLE_POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting idle", e);
            }
        }
    }

    @Override
    public void close() {
        for (SubscriptionDispatcher dispatcher : dispatchers) {
            dispatcher.close();
        }
        dispatchers.clear();
        queuesByDestination.clear();
    }
}
