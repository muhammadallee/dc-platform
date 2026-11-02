package ae.gov.dubaicustoms.platform.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void redactsTokensAndSecretValuesFromTheSnippet() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhIn0.c2lnbmF0dXJl";
        String body = "{\"password\":\"hunter2\",\"api_key\": \"k-1\",\"id\":\"keep\"} Authorization: Bearer abc.def token=xyz&next=1 " + jwt;

        String snippet = new RemoteCallException("orders", 500, body, null, null).bodySnippet();

        assertThat(snippet).doesNotContain("hunter2", "k-1", "abc.def", "xyz", jwt).contains("keep", "next=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Authorization: Basic dXNlcjpwYXNz",
        "x\nset-cookie: a=1; session=dXNlcjpwYXNz\ny",
        "{\"password\":\"a\\\"dXNlcjpwYXNz\"}",
        "<password>dXNlcjpwYXNz</password>",
        "<ns:clientSecret kind=\"x\">dXNlcjpwYXNz</ns:clientSecret>",
        "client_secret=dXNlcjpwYXNz&x=1",
        "access-token: dXNlcjpwYXNz"})
    void redactsCommonCredentialForms(String body) {
        assertThat(new RemoteCallException("orders", 500, body, null, null).bodySnippet()).doesNotContain("dXNlcjpwYXNz");
    }

    @Test
    void aHostileBodyIsHandledQuickly() {
        for (String body : new String[] {"token".repeat(1638), "\\a".repeat(4096), "\"token\":\"" + "\\a".repeat(4000), " ".repeat(8192),
            "<token".repeat(1365), "bearer ".repeat(1170)}) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> new RemoteCallException("orders", 500, body, null, null));
        }
    }

    @Test
    void redactsASecretCutOffByTheReadLimit() {
        String snippet = new RemoteCallException("orders", 500, "{\"token\":\"abcdef", null, null).bodySnippet();

        assertThat(snippet).doesNotContain("abcdef");
    }

    @Test
    void doesNotCutThroughACharacter() {
        String body = "x".repeat(1023) + "😀" + "tail";

        String snippet = new RemoteCallException("orders", 500, body, null, null).bodySnippet();

        assertThat(snippet).hasSize(1023).doesNotContain("\uD83D");
    }

    @Test
    void theBodyNeverReachesTheMessage() {
        assertThat(new RemoteCallException("orders", RemoteCallException.Kind.RESPONSE, 500, "private", true, null).getMessage())
                .doesNotContain("private").contains("500");
        assertThat(new RemoteCallException("orders", RemoteCallException.Kind.TIMEOUT, 0, "", true, null).getMessage()).contains("TIMEOUT");
    }

    @ParameterizedTest
    @CsvSource({
        "RESPONSE, 429, true, 503", "RESPONSE, 502, true, 503", "RESPONSE, 503, true, 503", "RESPONSE, 504, true, 503",
        "RESPONSE, 500, true, 500", "RESPONSE, 404, true, 500", "RESPONSE, 503, false, 500",
        "TIMEOUT, 0, true, 503", "TIMEOUT, 0, false, 500", "CONNECTION, 0, true, 503", "CONNECTION, 0, false, 500",
        "DEADLINE, 0, true, 504", "DEADLINE, 0, false, 500", "CIRCUIT_OPEN, 0, true, 503", "BULKHEAD_FULL, 0, true, 503"})
    void mapsAFailureToAProblemStatus(RemoteCallException.Kind kind, int status, boolean repeatable, int expected) {
        var exception = new RemoteCallException("orders", kind, status, "", repeatable, null);

        assertThat(exception.kind()).isEqualTo(kind);
        assertThat(exception.problemStatus()).isEqualTo(expected);
        assertThat(exception.retryable()).isEqualTo(expected != 500);
    }

    @Test
    void aResponseOfUnknownMethodIsNotAssumedRepeatable() {
        assertThat(new RemoteCallException("orders", 503, "", null, null).retryable()).isFalse();
    }

    @Test
    void rejectsNullClientNameAndBody() {
        assertThatThrownBy(() -> new RemoteCallException(null, 500, "body", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RemoteCallException("orders", 500, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
