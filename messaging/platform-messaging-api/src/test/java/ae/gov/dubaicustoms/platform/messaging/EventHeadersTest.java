package ae.gov.dubaicustoms.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventHeadersTest {

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "x-auth-token", "API_KEY", "db-password", "Set-Cookie", "client_secret"})
    void aCredentialNameIsRecognisedWhateverItsCase(String name) {
        assertThat(EventHeaders.isCredentialName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "traceparent", "producer", "tenant", "tokenized-count"})
    void anOrdinaryNameIsNot(String name) {
        assertThat(EventHeaders.isCredentialName(name)).isFalse();
    }
}
