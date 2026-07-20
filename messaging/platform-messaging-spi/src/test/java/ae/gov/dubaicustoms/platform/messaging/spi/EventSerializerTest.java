package ae.gov.dubaicustoms.platform.messaging.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EventSerializerTest {

    private final EventSerializer serializer = new EventSerializer() {
        @Override
        public byte[] serialize(Object payload) {
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T deserialize(byte[] bytes, Class<T> type) {
            return type.cast(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    };

    @Test
    void roundTripsThroughSerializeAndDeserialize() {
        byte[] bytes = serializer.serialize("hello");

        assertThat(serializer.deserialize(bytes, String.class)).isEqualTo("hello");
        assertThat(serializer.contentType()).isEqualTo("text/plain");
    }
}
