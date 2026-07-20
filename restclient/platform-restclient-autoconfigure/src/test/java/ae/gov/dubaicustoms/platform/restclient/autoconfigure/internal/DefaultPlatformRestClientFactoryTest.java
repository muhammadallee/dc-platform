package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DefaultPlatformRestClientFactoryTest {

    private final MockWebServer server = new MockWebServer();

    private final RestClientProperties defaultProperties =
            new RestClientProperties(true, true, new RestClientProperties.Defaults(null, null), Map.of());

    @BeforeEach
    void startServer() throws IOException {
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void propagatesCorrelationHeaderWhenContextOpen() throws Exception {
        server.enqueue(new MockResponse());
        RestClient client = new DefaultPlatformRestClientFactory(defaultProperties, List.of())
                .builder("orders").baseUrl(server.url("/").toString()).build();
        CorrelationId id = CorrelationId.random();

        try (AutoCloseable scope = RequestContext.open(id, Map.of())) {
            client.get().uri("/widgets").retrieve().toBodilessEntity();
        }

        assertThat(server.takeRequest().getHeader("X-Correlation-Id")).isEqualTo(id.value());
    }

    @Test
    void doesNotSetCorrelationHeaderWhenPropagationDisabled() throws Exception {
        server.enqueue(new MockResponse());
        RestClientProperties disabled =
                new RestClientProperties(true, false, new RestClientProperties.Defaults(null, null), Map.of());
        RestClient client = new DefaultPlatformRestClientFactory(disabled, List.of())
                .builder("orders").baseUrl(server.url("/").toString()).build();

        try (AutoCloseable scope = RequestContext.open(CorrelationId.random(), Map.of())) {
            client.get().uri("/widgets").retrieve().toBodilessEntity();
        }

        assertThat(server.takeRequest().getHeader("X-Correlation-Id")).isNull();
    }

    @Test
    void mapsNon2xxResponsesToRemoteCallException() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("service unavailable")
                .addHeader("X-Correlation-Id", "remote-abc"));
        RestClient client = new DefaultPlatformRestClientFactory(defaultProperties, List.of())
                .builder("orders").baseUrl(server.url("/").toString()).build();

        assertThatThrownBy(() -> client.get().uri("/widgets").retrieve().toBodilessEntity())
                .isInstanceOf(RemoteCallException.class)
                .satisfies(e -> {
                    RemoteCallException remoteCallException = (RemoteCallException) e;
                    assertThat(remoteCallException.status()).isEqualTo(503);
                    assertThat(remoteCallException.bodySnippet()).isEqualTo("service unavailable");
                    assertThat(remoteCallException.remoteCorrelationId()).contains("remote-abc");
                });
    }

    @Test
    void appliesCustomizersInOrder() {
        server.enqueue(new MockResponse());
        List<String> calls = new ArrayList<>();
        RestClient client = new DefaultPlatformRestClientFactory(defaultProperties,
                List.of((name, builder) -> calls.add("first"), (name, builder) -> calls.add("second")))
                .builder("orders").baseUrl(server.url("/").toString()).build();

        client.get().uri("/widgets").retrieve().toBodilessEntity();

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void appliesPerClientTimeoutOverride() {
        RestClientProperties withOverride = new RestClientProperties(true, true, new RestClientProperties.Defaults(null, null),
                Map.of("orders", new RestClientProperties.ClientOverride(Duration.ofMillis(1), null)));

        RestClientProperties.Defaults resolved = withOverride.timeoutsFor("orders");

        assertThat(resolved.connectTimeout()).isEqualTo(Duration.ofMillis(1));
        assertThat(resolved.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
