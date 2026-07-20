package ae.gov.dubaicustoms.platform.messaging.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventTransportTest {

    @Test
    void listenerReceivesKeyValueAndHeaders() {
        List<String> received = new ArrayList<>();
        EventTransport.TransportListener listener = (key, value, headers) ->
                received.add(new String(value) + ":" + headers.get("eventType"));

        listener.onMessage("k".getBytes(), "v".getBytes(), Map.of("eventType", "OrderPlaced"));

        assertThat(received).containsExactly("v:OrderPlaced");
    }

    @Test
    void listenerThrowingIsPropagatedAsNack() {
        EventTransport.TransportListener listener = (key, value, headers) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> listener.onMessage(null, "v".getBytes(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }
}
