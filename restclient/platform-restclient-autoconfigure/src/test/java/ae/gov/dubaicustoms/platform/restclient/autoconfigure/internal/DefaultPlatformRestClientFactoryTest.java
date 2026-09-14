package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.web.client.ResourceAccessException;
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
    void readTimeoutAbortsASlowResponseWithinTheConfiguredBound() {
        server.enqueue(new MockResponse().setBody("late").setHeadersDelay(3, TimeUnit.SECONDS));
        RestClientProperties shortRead = new RestClientProperties(true, true,
                new RestClientProperties.Defaults(null, Duration.ofMillis(300)), Map.of());
        RestClient client = new DefaultPlatformRestClientFactory(shortRead, List.of())
                .builder("orders").baseUrl(server.url("/").toString()).build();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> client.get().uri("/slow").retrieve().toBodilessEntity())
                        .isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void perClientReadTimeoutOverridesTheDefaultAtCallTime() {
        server.enqueue(new MockResponse().setBody("late").setHeadersDelay(3, TimeUnit.SECONDS));
        RestClientProperties overridden = new RestClientProperties(true, true, new RestClientProperties.Defaults(null, null),
                Map.of("orders", new RestClientProperties.ClientOverride(null, Duration.ofMillis(300))));
        RestClient client = new DefaultPlatformRestClientFactory(overridden, List.of())
                .builder("orders").baseUrl(server.url("/").toString()).build();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> client.get().uri("/slow").retrieve().toBodilessEntity())
                        .isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void refusedConnectionSurfacesAsResourceAccessException() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        RestClient client = new DefaultPlatformRestClientFactory(defaultProperties, List.of())
                .builder("orders").baseUrl("http://127.0.0.1:" + closedPort).build();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> client.get().uri("/widgets").retrieve().toBodilessEntity())
                        .isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void credentialsSetByAnInterceptorAreNotForwardedToAnotherOriginOnRedirect() throws Exception {
        // The token relay sets Authorization in a request interceptor; a redirect to a different origin
        // (different port here) must not carry it along.
        try (MockWebServer elsewhere = new MockWebServer()) {
            elsewhere.start();
            elsewhere.enqueue(new MockResponse().setBody("landed"));
            server.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", elsewhere.url("/landing").toString()));
            RestClient client = new DefaultPlatformRestClientFactory(defaultProperties, List.of((name, builder) ->
                    builder.requestInterceptor((request, body, execution) -> {
                        request.getHeaders().setBearerAuth("relayed-user-token");
                        return execution.execute(request, body);
                    })))
                    .builder("orders").baseUrl(server.url("/").toString()).build();

            client.get().uri("/start").retrieve().toBodilessEntity();

            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer relayed-user-token");
            RecordedRequest redirected = elsewhere.takeRequest(5, TimeUnit.SECONDS);
            assertThat(redirected).as("the redirect was followed").isNotNull();
            assertThat(redirected.getHeader("Authorization")).isNull();
        }
    }

    @Test
    void recordsAnHttpClientObservationTaggedWithThePlatformClientName() {
        server.enqueue(new MockResponse());
        List<Observation.Context> observed = new CopyOnWriteArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                observed.add(context);
            }
        });
        RestClient client = new DefaultPlatformRestClientFactory(defaultProperties, List.of(), () -> registry)
                .builder("orders").baseUrl(server.url("/").toString()).build();

        client.get().uri("/widgets").retrieve().toBodilessEntity();

        assertThat(observed).singleElement().satisfies(context -> {
            assertThat(context).isInstanceOf(ClientRequestObservationContext.class);
            assertThat(context.getName()).isEqualTo("http.client.requests");
            assertThat(context.getLowCardinalityKeyValue("client.name").getValue()).isEqualTo("orders");
            assertThat(context.getLowCardinalityKeyValue("status").getValue()).isEqualTo("200");
        });
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
