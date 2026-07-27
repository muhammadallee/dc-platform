package ae.gov.dubaicustoms.example.eventdriven.producer;

/**
 * The event contract, as owned by the producer. In an event-driven system the producer and consumer
 * are separate deployables that each hold their own copy of the payload shape; the platform serializer
 * matches them by JSON structure and the {@code eventType} header (the simple class name,
 * {@code ShipmentRequested}), not by a shared class.
 *
 * @param shipmentId the shipment identifier
 * @param destination the delivery destination
 */
public record ShipmentRequested(String shipmentId, String destination) {

    /** The logical destination order events flow over. */
    public static final String CHANNEL = "dc.shipments";
}
