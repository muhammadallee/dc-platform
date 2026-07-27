package ae.gov.dubaicustoms.example.eventdriven.consumer;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles {@code ShipmentRequested}. A shipment whose id is {@value #POISON} always throws, so it
 * exercises the platform's retry-then-DLQ path; every other shipment is processed once. The counters
 * let {@code ShipmentRetryDlqTest} observe how many delivery attempts the platform made.
 */
@Component
public class ShipmentHandler {

    /** A shipment id that always fails, to demonstrate retry/DLQ. */
    public static final String POISON = "poison";

    private static final Logger log = LoggerFactory.getLogger(ShipmentHandler.class);

    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger attempts = new AtomicInteger();

    @ae.gov.dubaicustoms.platform.messaging.EventHandler(
            destination = ShipmentRequested.CHANNEL, eventType = "ShipmentRequested")
    public void onShipment(ShipmentRequested event) {
        if (POISON.equals(event.shipmentId())) {
            int attempt = attempts.incrementAndGet();
            log.warn("processing shipment {} failed (attempt {})", event.shipmentId(), attempt);
            throw new IllegalStateException("cannot process poison shipment " + event.shipmentId());
        }
        processed.incrementAndGet();
        log.info("processed shipment {} to {}", event.shipmentId(), event.destination());
    }

    /** @return how many shipments were processed successfully */
    public int processedCount() {
        return processed.get();
    }

    /** @return how many delivery attempts the poison shipment received */
    public int attemptCount() {
        return attempts.get();
    }
}
