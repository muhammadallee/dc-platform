package ae.gov.dubaicustoms.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventPublishExceptionTest {

    @Test
    void carriesCodeMessageAndCause() {
        var cause = new RuntimeException("boom");

        var exception = new EventPublishException("publish failed", cause);

        assertThat(exception.code().value()).isEqualTo("DC-MSG-0001");
        assertThat(exception.getMessage()).isEqualTo("publish failed");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void rejectsNullMessage() {
        assertThatThrownBy(() -> new EventPublishException(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
