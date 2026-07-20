package ae.gov.dubaicustoms.platform.messaging;

/**
 * Publishes integration events to the configured transport.
 *
 * <p><strong>Delivery semantics: AT-LEAST-ONCE.</strong> {@code publish} blocks until the
 * transport acknowledges the send; a normal return means the transport has accepted the event,
 * not that every subscriber has processed it. Failures throw {@link EventPublishException}; the
 * platform does not silently retry a failed publish beyond what the transport itself guarantees —
 * callers needing at-most-once or exactly-once effects must de-duplicate downstream (see the
 * idempotency capability, phase 9).
 *
 * <pre>{@code
 * publisher.publish("dc.orders", new OrderPlaced(orderId));
 * }</pre>
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public interface EventPublisher {

    /**
     * Publishes {@code event} to {@code destination}, blocking until the transport acknowledges
     * it.
     *
     * @param destination the transport-specific destination (topic, exchange, queue); never
     *     {@code null}
     * @param event the envelope to publish; never {@code null}
     * @throws EventPublishException if the transport rejects or fails to acknowledge the send
     */
    void publish(String destination, EventEnvelope<?> event);

    /**
     * Convenience overload that wraps {@code payload} in a fresh {@link EventEnvelope} via
     * {@link EventEnvelope#of(Object)} before publishing.
     *
     * @param destination the transport-specific destination; never {@code null}
     * @param payload the event payload; never {@code null}
     * @throws EventPublishException if the transport rejects or fails to acknowledge the send
     */
    default void publish(String destination, Object payload) {
        publish(destination, EventEnvelope.of(payload).build());
    }
}
