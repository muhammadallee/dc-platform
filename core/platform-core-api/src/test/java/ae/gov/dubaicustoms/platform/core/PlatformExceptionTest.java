package ae.gov.dubaicustoms.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class PlatformExceptionTest {

    private static final ErrorCode CODE = new ErrorCode("DC-CORE-0500");

    private static final class TestException extends PlatformException {
        TestException(String message) {
            super(CODE, message);
        }

        TestException(String message, Throwable cause) {
            super(CODE, message, cause);
        }
    }

    @Test
    void carriesCodeAndMessage() {
        TestException exception = new TestException("boom");

        assertThat(exception.code()).isEqualTo(CODE);
        assertThat(exception).hasMessage("boom").hasNoCause();
    }

    @Test
    void carriesCause() {
        Throwable cause = new IllegalStateException("root");

        assertThat(new TestException("boom", cause)).hasCause(cause);
    }

    @Test
    void rejectsNullCode() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PlatformException(null, "boom") { })
                .withMessageContaining("code");
    }

    @Test
    void rejectsNullMessage() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PlatformException(CODE, null) { })
                .withMessageContaining("message");
    }
}
