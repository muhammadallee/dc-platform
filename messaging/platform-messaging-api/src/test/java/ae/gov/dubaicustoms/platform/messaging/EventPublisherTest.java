package ae.gov.dubaicustoms.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventPublisherTest {

    @Test
    void defaultPublishOverloadWrapsPayloadInEnvelope() {
        List<EventEnvelope<?>> published = new ArrayList<>();
        EventPublisher publisher = (destination, event) -> published.add(event);

        publisher.publish("dc.orders", "payload");

        assertThat(published).hasSize(1);
        assertThat(published.get(0).payload()).isEqualTo("payload");
        assertThat(published.get(0).eventType()).isEqualTo("String");
    }
}
