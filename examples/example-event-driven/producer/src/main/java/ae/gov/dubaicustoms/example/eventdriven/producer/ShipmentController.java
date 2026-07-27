package ae.gov.dubaicustoms.example.eventdriven.producer;

import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes a {@code ShipmentRequested} when asked. The correlation id of the inbound HTTP request
 * rides along on the event automatically, so the consumer's logs line up with the producer's.
 */
@RestController
public class ShipmentController {

    private final EventPublisher publisher;

    public ShipmentController(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/shipments/{id}")
    public ResponseEntity<Void> requestShipment(@PathVariable String id) {
        publisher.publish(ShipmentRequested.CHANNEL, new ShipmentRequested(id, "Jebel Ali"));
        return ResponseEntity.accepted().build();
    }
}
