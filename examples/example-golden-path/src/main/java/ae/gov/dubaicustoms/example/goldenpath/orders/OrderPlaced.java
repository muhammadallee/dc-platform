package ae.gov.dubaicustoms.example.goldenpath.orders;

import java.math.BigDecimal;

/**
 * Domain event published when an order is placed. It is a plain record; the platform's event
 * serializer turns it into bytes and back, and correlation IDs ride along automatically.
 *
 * @param orderId the placed order's id
 * @param customer the ordering party
 * @param amount the order total
 */
public record OrderPlaced(Long orderId, String customer, BigDecimal amount) {

    /** The logical destination (topic/queue/stream name) order events flow over. */
    public static final String DESTINATION = "dc.orders";
}
