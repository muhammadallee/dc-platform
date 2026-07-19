package ae.gov.dubaicustoms.platform.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorrelationIdTest {

    @Test
    void randomProducesThirtyTwoLowercaseHexCharacters() {
        assertThat(CorrelationId.random().value()).matches("[0-9a-f]{32}");
    }

    @Test
    void randomProducesDistinctValues() {
        assertThat(CorrelationId.random()).isNotEqualTo(CorrelationId.random());
    }

    @Test
    void acceptsWellFormedValue() {
        String value = "0123456789abcdef0123456789abcdef";

        assertThat(new CorrelationId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0123456789ABCDEF0123456789ABCDEF",     // uppercase
        "0123456789abcdef0123456789abcde",      // 31 chars
        "0123456789abcdef0123456789abcdef0",    // 33 chars
        "01234567-89ab-cdef-0123-456789abcdef", // dashed UUID
        ""
    })
    void rejectsMalformedValues(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CorrelationId(value))
                .withMessageContaining("32 lowercase hex");
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new CorrelationId(null));
    }
}
