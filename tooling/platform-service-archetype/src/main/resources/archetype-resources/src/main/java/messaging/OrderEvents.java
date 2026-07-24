#if($features.contains("messaging"))
package ${package}.messaging;

import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Feature sample (features=messaging): publish domain events via {@link EventPublisher} and consume
 * them via {@link EventHandler}. Retry, DLQ, correlation propagation, serialization, and metrics are
 * handled by the platform — never inject a broker template or wire a listener container directly.
 */
@Component
public class OrderEvents {

    private static final Logger log = LoggerFactory.getLogger(OrderEvents.class);
    private static final String DESTINATION = "dc.orders";

    private final EventPublisher publisher;

    public OrderEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Publish an order-placed event. Correlation IDs ride along automatically. */
    public void placeOrder(String orderId) {
        publisher.publish(DESTINATION, "order-placed:" + orderId);
    }

    /** Consume order events from the same destination. */
    @EventHandler(destination = DESTINATION)
    public void onOrder(String payload) {
        log.info("received order event: {}", payload);
    }
}
#end
