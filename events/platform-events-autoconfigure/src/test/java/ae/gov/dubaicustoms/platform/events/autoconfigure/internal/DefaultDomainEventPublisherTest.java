package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

class DefaultDomainEventPublisherTest {

    record OrderPlaced(String orderId) implements DomainEvent {}

    @Test
    void delegatesToApplicationEventPublisher() {
        var context = new StaticApplicationContext();
        List<Object> received = new ArrayList<>();
        context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payloadEvent) {
                received.add(payloadEvent.getPayload());
            }
        });
        context.refresh();
        var publisher = new DefaultDomainEventPublisher(context);

        publisher.publish(new OrderPlaced("o-1"));

        assertThat(received).containsExactly(new OrderPlaced("o-1"));
    }
}
