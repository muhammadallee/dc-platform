package ae.gov.dubaicustoms.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainEventPublisherTest {

    record OrderPlaced(String orderId) implements DomainEvent {}

    @Test
    void invokesTheLambdaWithThePublishedEvent() {
        List<DomainEvent> published = new ArrayList<>();
        DomainEventPublisher publisher = published::add;

        publisher.publish(new OrderPlaced("o-1"));

        assertThat(published).containsExactly(new OrderPlaced("o-1"));
    }
}
