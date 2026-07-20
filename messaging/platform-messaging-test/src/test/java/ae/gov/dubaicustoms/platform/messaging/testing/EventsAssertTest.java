package ae.gov.dubaicustoms.platform.messaging.testing;

import static ae.gov.dubaicustoms.platform.messaging.testing.EventsAssert.assertThatEvents;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EventsAssertTest {

    @Test
    void passesWhenEventOfExpectedTypeWasSentToDestination() {
        var transport = new TestEventTransport();
        transport.send("dc.orders", null, "payload".getBytes(), Map.of("eventType", "OrderPlaced"));

        assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
    }

    @Test
    void failsWhenNothingWasSentToTheDestination() {
        var transport = new TestEventTransport();

        assertThatThrownBy(() -> assertThatEvents(transport).sentTo("dc.orders"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void failsWhenSentEventTypeDoesNotMatch() {
        var transport = new TestEventTransport();
        transport.send("dc.orders", null, "payload".getBytes(), Map.of("eventType", "OrderCancelled"));

        assertThatThrownBy(() -> assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void withTypeRequiresSentToFirst() {
        var transport = new TestEventTransport();

        assertThatThrownBy(() -> assertThatEvents(transport).withType("OrderPlaced"))
                .isInstanceOf(IllegalStateException.class);
    }
}
