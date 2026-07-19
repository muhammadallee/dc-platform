package ae.gov.dubaicustoms.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class KvTest {

    @Test
    void rendersKeyEqualsValue() {
        assertThat(Kv.of("orderId", 42)).hasToString("orderId=42");
    }

    @Test
    void nullValueRendersLiterally() {
        assertThat(Kv.of("orderId", null)).hasToString("orderId=null");
        assertThat(Kv.of("orderId", null).value()).isNull();
    }

    @Test
    void rejectsNullKey() {
        assertThatNullPointerException().isThrownBy(() -> Kv.of(null, "x"));
    }

    @Test
    void isAValueObject() {
        assertThat(Kv.of("a", 1)).isEqualTo(new Kv("a", 1));
    }
}
