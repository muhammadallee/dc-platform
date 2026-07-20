package ae.gov.dubaicustoms.platform.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RemoteCallExceptionTest {

    @Test
    void carriesStatusBodyAndRemoteCorrelationId() {
        var exception = new RemoteCallException("orders", 503, "service unavailable", "abc123", null);

        assertThat(exception.status()).isEqualTo(503);
        assertThat(exception.bodySnippet()).isEqualTo("service unavailable");
        assertThat(exception.remoteCorrelationId()).contains("abc123");
        assertThat(exception.code().value()).isEqualTo("DC-RCLIENT-0500");
    }

    @Test
    void remoteCorrelationIdIsEmptyWhenAbsent() {
        var exception = new RemoteCallException("orders", 500, "boom", null, null);

        assertThat(exception.remoteCorrelationId()).isEmpty();
    }

    @Test
    void truncatesBodyTo1Kb() {
        String longBody = "x".repeat(2048);

        var exception = new RemoteCallException("orders", 500, longBody, null, null);

        assertThat(exception.bodySnippet()).hasSize(1024);
    }

    @Test
    void rejectsNullClientNameAndBody() {
        assertThatThrownBy(() -> new RemoteCallException(null, 500, "body", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RemoteCallException("orders", 500, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
