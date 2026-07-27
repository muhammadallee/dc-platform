package ae.gov.dubaicustoms.example.eventdriven.consumer;

import static ae.gov.dubaicustoms.platform.messaging.testing.EventsAssert.assertThatEvents;
import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.testing.TestEventTransport;
import ae.gov.dubaicustoms.platform.messaging.testing.TestEventTransport.SentEvent;
import ae.gov.dubaicustoms.platform.test.junit.PlatformMessagingTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The retry/DLQ demonstration: a poison shipment makes the handler throw on every attempt, so the
 * platform retries up to {@code maxAttempts} (3 by default) and then republishes the message to the
 * DLQ destination ({@code channel + ".dlq"}). Backoff is shortened here so the test is fast; the
 * behaviour is the platform's, not this example's.
 */
@PlatformMessagingTest
@TestPropertySource(properties = {
        "dc.platform.messaging.handler.retry.max-attempts=3",
        "dc.platform.messaging.handler.retry.backoff=10ms"
})
class ShipmentRetryDlqTest {

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private TestEventTransport transport;

    @Autowired
    private ShipmentHandler handler;

    @Test
    void poisonShipmentIsRetriedThenRoutedToDlq() {
        publisher.publish(ShipmentRequested.CHANNEL, new ShipmentRequested(ShipmentHandler.POISON, "nowhere"));

        SentEvent poison = transport.sent().getLast();
        transport.clearSent(); // so the only remaining recorded send is the DLQ republish
        transport.deliver(poison.destination(), poison.key(), poison.value(), poison.headers());

        assertThat(handler.attemptCount()).isEqualTo(3);
        assertThat(handler.processedCount()).isZero();
        assertThatEvents(transport).sentTo(ShipmentRequested.CHANNEL + ".dlq");
    }
}
