package ae.gov.dubaicustoms.platform.redis.autoconfigure.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrefixingStringRedisSerializerTest {

    private final PrefixingStringRedisSerializer serializer = new PrefixingStringRedisSerializer("orders:");

    @Test
    void prependsPrefixOnSerialize() {
        assertThat(new String(serializer.serialize("42"), UTF_8)).isEqualTo("orders:42");
    }

    @Test
    void stripsPrefixOnDeserialize() {
        assertThat(serializer.deserialize("orders:42".getBytes(UTF_8))).isEqualTo("42");
    }

    @Test
    void leavesUnprefixedValueUntouchedOnDeserialize() {
        assertThat(serializer.deserialize("42".getBytes(UTF_8))).isEqualTo("42");
    }

    @Test
    void handlesNulls() {
        assertThat(serializer.serialize(null)).isNull();
        assertThat(serializer.deserialize(null)).isNull();
    }
}
