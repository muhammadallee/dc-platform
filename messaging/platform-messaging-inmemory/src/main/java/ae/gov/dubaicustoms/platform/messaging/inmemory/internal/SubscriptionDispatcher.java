package ae.gov.dubaicustoms.platform.messaging.inmemory.internal;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport.TransportListener;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls {@link QueuedMessage}s off a shared per-(destination, group) queue and delivers them to
 * one {@link TransportListener}, on its own virtual thread.
 *
 * <p>Redelivery: a thrown exception from the listener requeues the message (attempts incremented)
 * after a fixed backoff, up to {@code maxAttempts}; beyond that the message is dropped and logged
 * at ERROR with code {@code DC-MSG-0500}. This is the reference implementation's own policy, not a
 * platform-wide contract — {@code platform-messaging-autoconfigure}'s {@code EventHandlerRegistrar}
 * layers a second, transport-agnostic retry/DLQ policy on top (honoring
 * {@code dc.platform.messaging.handler.retry.*}), and kafka/rabbit use their own broker-native DLQ
 * mechanisms instead of this class.
 *
 * <p>When two {@code subscribe} calls share the same {@code destination}/{@code group}, they share
 * the same queue (each gets its own {@code SubscriptionDispatcher} instance pulling from it), which
 * gives natural competing-consumer semantics without any extra coordination code.
 *
 * <p>Internal: not part of the messaging contract.
 */
public final class SubscriptionDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDispatcher.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final BlockingQueue<QueuedMessage> queue;
    private final TransportListener listener;
    private final String destination;
    private final String group;
    private final int maxAttempts;
    private final Duration redeliveryBackoff;
    private final AtomicLong inFlight;
    private final Thread worker;
    private volatile boolean stopped;

    /**
     * Starts the dispatcher on a new virtual thread.
     *
     * @param queue the shared queue for this (destination, group) pair; never {@code null}
     * @param listener the handler to deliver messages to; never {@code null}
     * @param destination the destination name, used only for log/thread naming
     * @param group the consumer group name, used only for log/thread naming
     * @param maxAttempts total delivery attempts before dropping a message; at least 1
     * @param redeliveryBackoff pause before each redelivery attempt
     * @param inFlight shared counter this transport uses for {@code awaitIdle}; decremented once
     *     per message, whether it is ultimately handled or dropped
     */
    public SubscriptionDispatcher(BlockingQueue<QueuedMessage> queue, TransportListener listener, String destination,
            String group, int maxAttempts, Duration redeliveryBackoff, AtomicLong inFlight) {
        this.queue = queue;
        this.listener = listener;
        this.destination = destination;
        this.group = group;
        this.maxAttempts = maxAttempts;
        this.redeliveryBackoff = redeliveryBackoff;
        this.inFlight = inFlight;
        this.worker = Thread.ofVirtual()
                .name("messaging-inmemory-" + destination + "-" + group)
                .start(this::loop);
    }

    private void loop() {
        while (!stopped) {
            QueuedMessage message;
            try {
                // Bounded poll (not take()) so `stopped` is re-checked promptly on close().
                message = queue.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (message != null) {
                deliver(message);
            }
        }
    }

    private void deliver(QueuedMessage message) {
        try {
            listener.onMessage(message.key(), message.value(), message.headers());
            inFlight.decrementAndGet();
        } catch (RuntimeException e) {
            if (message.attempts() + 1 >= maxAttempts) {
                log.error("[DC-MSG-0500] dropping message on destination={} group={} after {} attempt(s): {}",
                        destination, group, message.attempts() + 1, e.getMessage(), e);
                inFlight.decrementAndGet();
                return;
            }
            sleepBackoff();
            queue.offer(message.withAttemptIncremented());
        }
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(redeliveryBackoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stopped = true;
        worker.interrupt();
    }
}
