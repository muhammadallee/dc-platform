package ae.gov.dubaicustoms.example.eventdriven.consumer;

/**
 * The consumer's own copy of the event contract (see the producer's identical record for why each
 * side owns one). Fields and the {@code eventType} header (simple name {@code ShipmentRequested})
 * must match the producer's for deserialization to line up.
 *
 * @param shipmentId the shipment identifier
 * @param destination the delivery destination
 */
public record ShipmentRequested(String shipmentId, String destination) {

    /** The logical destination shipment events flow over. */
    public static final String CHANNEL = "dc.shipments";
}
