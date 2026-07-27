package ae.gov.dubaicustoms.platform.events;

import org.apiguardian.api.API;

/**
 * Publishes {@link DomainEvent}s in-process. {@code platform-events-autoconfigure} bridges this to
 * Spring's {@code ApplicationEventPublisher}; handler methods marked {@link DomainEventHandler}
 * receive the event, dispatched after the enclosing transaction commits when one is active
 * (immediately otherwise).
 *
 * <pre>{@code
 * class OrdersService {
 *     private final DomainEventPublisher publisher;
 *
 *     void place(String orderId) {
 *         // ... persist the order ...
 *         publisher.publish(new OrderPlaced(orderId));
 *     }
 * }
 * }</pre>
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public interface DomainEventPublisher {

    /**
     * Publishes {@code event} in-process.
     *
     * @param event the event to publish; never {@code null}
     */
    void publish(DomainEvent event);
}
