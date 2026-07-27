package ae.gov.dubaicustoms.example.eventdriven.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.testing.TestEventTransport;
import ae.gov.dubaicustoms.platform.messaging.testing.TestEventTransport.SentEvent;
import ae.gov.dubaicustoms.platform.test.junit.PlatformMessagingTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A good {@code ShipmentRequested} is delivered to the {@code @EventHandler} and processed once. The
 * event is published through {@link EventPublisher} (so the platform serializes it exactly as in
 * production) and then delivered via the recording {@link TestEventTransport}, no broker required.
 */
@PlatformMessagingTest
class ShipmentRoundTripTest {

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private TestEventTransport transport;

    @Autowired
    private ShipmentHandler handler;

    @Test
    void deliveredShipmentIsProcessedOnce() {
        publisher.publish(ShipmentRequested.CHANNEL, new ShipmentRequested("S-1", "Jebel Ali"));

        SentEvent published = transport.sent().getLast();
        transport.deliver(published.destination(), published.key(), published.value(), published.headers());

        assertThat(handler.processedCount()).isEqualTo(1);
    }
}
