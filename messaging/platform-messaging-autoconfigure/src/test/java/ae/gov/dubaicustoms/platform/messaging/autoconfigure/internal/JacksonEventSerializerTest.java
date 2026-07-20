package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.messaging.EventSerializationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JacksonEventSerializerTest {

    record OrderPlaced(String orderId) {}

    private final JacksonEventSerializer serializer = new JacksonEventSerializer(new ObjectMapper());

    @Test
    void roundTripsThroughJson() {
        byte[] bytes = serializer.serialize(new OrderPlaced("o-1"));

        assertThat(new String(bytes)).contains("o-1");
        assertThat(serializer.deserialize(bytes, OrderPlaced.class)).isEqualTo(new OrderPlaced("o-1"));
        assertThat(serializer.contentType()).isEqualTo("application/json");
    }

    @Test
    void deserializeThrowsEventSerializationExceptionOnInvalidJson() {
        assertThatThrownBy(() -> serializer.deserialize("not json".getBytes(), OrderPlaced.class))
                .isInstanceOf(EventSerializationException.class)
                .extracting(e -> ((EventSerializationException) e).code().value())
                .isEqualTo("DC-MSG-0002");
    }
}
