package ae.gov.dubaicustoms.example.goldenpath.orders;

import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link OrderPlaced} from the same destination it is published to. The platform delivers a
 * deserialized {@code OrderPlaced} (in-memory transport locally); retry, DLQ, correlation propagation,
 * and metrics are handled by the platform, so this method only expresses what to do with the event.
 */
@Component
public class OrderPlacedHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedHandler.class);

    @EventHandler(destination = OrderPlaced.DESTINATION)
    public void onOrderPlaced(OrderPlaced event) {
        log.info("order placed: id={} customer={} amount={}", event.orderId(), event.customer(), event.amount());
    }
}
