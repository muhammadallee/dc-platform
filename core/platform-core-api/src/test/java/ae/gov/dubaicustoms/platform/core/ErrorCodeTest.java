package ae.gov.dubaicustoms.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ErrorCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"DC-MSG-0001", "DC-CO-0400", "DC-SECURITY-0599", "DC-CORE-9999"})
    void acceptsWellFormedCodes(String code) {
        assertThat(new ErrorCode(code).value()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ACME-MSG-0001",      // wrong namespace
        "DC-msg-0001",        // lowercase capability
        "DC-M-0001",          // capability too short
        "DC-VERYLONGCAP-0001", // capability too long
        "DC-MSG-001",         // three digits
        "DC-MSG-00011",       // five digits
        "DC-MSG-0001 ",       // trailing whitespace
        "DC-MSG",             // missing number
        ""
    })
    void rejectsMalformedCodes(String code) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ErrorCode(code))
                .withMessageContaining("DC-<CAP>-<NNNN>");
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new ErrorCode(null));
    }
}
