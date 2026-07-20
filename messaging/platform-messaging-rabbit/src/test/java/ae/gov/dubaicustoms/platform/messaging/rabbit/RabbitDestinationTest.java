package ae.gov.dubaicustoms.platform.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RabbitDestinationTest {

    @Test
    void parsesExchangeOnlySyntaxWithEmptyRoutingKey() {
        var dest = RabbitDestination.parse("dc.orders");

        assertThat(dest.exchange()).isEqualTo("dc.orders");
        assertThat(dest.routingKey()).isEmpty();
    }

    @Test
    void parsesExchangeAndRoutingKeySyntax() {
        var dest = RabbitDestination.parse("dc.orders:order.placed");

        assertThat(dest.exchange()).isEqualTo("dc.orders");
        assertThat(dest.routingKey()).isEqualTo("order.placed");
    }

    @Test
    void rejectsNullDestination() {
        assertThatThrownBy(() -> RabbitDestination.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
