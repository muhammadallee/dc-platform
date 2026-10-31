package ae.gov.dubaicustoms.platform.messaging;

import org.apiguardian.api.API;

/**
 * Publishes an integration event **in the caller's database transaction**: the event commits or rolls
 * back with the business write, and is delivered later, at least once. Use it for any publish tied to a
 * database change; {@link EventPublisher} sends immediately and cannot give that guarantee.
 *
 * <pre>{@code
 * @Transactional
 * public void place(Order order) {
 *     orders.save(order);
 *     outbox.publish("dc.orders:order.placed", EventEnvelope.of(new OrderPlaced(order.id())).key(order.id()).build());
 * }
 * }</pre>
 *
 * <p>Delivery is at-least-once and unordered: a consumer can see an event twice, or a later event first,
 * and deduplicates by {@link EventEnvelope#eventId()}.
 *
 * <p>Implementations are thread-safe.
 *
 * @since 1.0.0
 */
@API(status = API.Status.STABLE, since = "1.0.0")
public interface OutboxPublisher {

    /**
     * Stores {@code event} for delivery to {@code destination}, inside the active transaction.
     *
     * @param destination the transport-specific destination; never {@code null}
     * @param event the envelope to store; never {@code null}
     * @throws IllegalStateException if no transaction is active on the outbox's database: the event could
     *     not commit atomically with the business write
     * @throws IllegalArgumentException if a header is named like a credential: stored rows must not hold one
     */
    void publish(String destination, EventEnvelope<?> event);
}
