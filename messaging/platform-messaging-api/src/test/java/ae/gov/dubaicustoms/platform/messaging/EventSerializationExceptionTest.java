package ae.gov.dubaicustoms.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventSerializationExceptionTest {

    @Test
    void carriesCodeMessageAndCause() {
        var cause = new RuntimeException("boom");

        var exception = new EventSerializationException("serialize failed", cause);

        assertThat(exception.code().value()).isEqualTo("DC-MSG-0002");
        assertThat(exception.getMessage()).isEqualTo("serialize failed");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void rejectsNullMessage() {
        assertThatThrownBy(() -> new EventSerializationException(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
