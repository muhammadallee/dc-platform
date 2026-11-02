package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.web.client.RestClient;

/**
 * Default {@link PlatformRestClientFactory}: one JDK {@link HttpClient} per client name under the platform's resilience,
 * plus {@code http.client.requests} observations tagged {@code client.name=<clientName>}. Closes its clients with the
 * context.
 */
public final class DefaultPlatformRestClientFactory implements PlatformRestClientFactory, DisposableBean {

    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final RestClientProperties properties;
    private final List<PlatformRestClientCustomizer> customizers;
    private final Supplier<ObservationRegistry> observationRegistry;
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final Map<String, HttpClient> httpClients = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, String> attempts = (client, outcome) -> { };

    public DefaultPlatformRestClientFactory(RestClientProperties properties, List<PlatformRestClientCustomizer> customizers) {
        this(properties, customizers, () -> ObservationRegistry.NOOP);
    }

    /**
     * @param observationRegistry resolved on every {@link #builder(String)} call (lazily, so the factory
     *        never depends on the observability auto-configuration's ordering); NOOP when none exists
     */
    public DefaultPlatformRestClientFactory(RestClientProperties properties, List<PlatformRestClientCustomizer> customizers,
            Supplier<ObservationRegistry> observationRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.customizers = List.copyOf(customizers);
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
    }

    @Override
    public RestClient.Builder builder(String clientName) {
        Objects.requireNonNull(clientName, "clientName must not be null");
        if (clientName.isBlank()) {
            throw new IllegalArgumentException("clientName must not be blank");
        }
        RestClientProperties.Defaults settings = properties.timeoutsFor(clientName);
        // A listener resolved per attempt, so metrics bound after a client is built still count it.
        ResilientRequestFactory requestFactory = new ResilientRequestFactory(clientName,
                httpClients.computeIfAbsent(clientName, name -> httpClient(settings)), settings, breaker(clientName, settings),
                bulkhead(clientName, settings), (client, outcome) -> attempts.accept(client, outcome));

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry.get())
                .observationConvention(new ClientNameConvention(clientName));
        // No correlation interceptor: the observation registry above propagates traceparent, the
        // platform's only correlation id. No custom correlation header is sent or read.
        builder.defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (request, response) -> {
            boolean repeatable = !HttpMethod.POST.equals(request.getMethod()) && !HttpMethod.PATCH.equals(request.getMethod());
            throw new RemoteCallException(clientName, RemoteCallException.Kind.RESPONSE, response.getStatusCode().value(),
                    readBody(response), repeatable, null);
        });
        for (PlatformRestClientCustomizer customizer : customizers) {
            customizer.customize(clientName, builder);
        }
        return builder;
    }

    /** Reads what is needed and no more: the rest is dropped when the response closes. */
    private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            return new String(in.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException | RemoteCallException e) {
            return "";
        }
    }

    private static HttpClient httpClient(RestClientProperties.Defaults settings) {
        return HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private CircuitBreaker breaker(String clientName, RestClientProperties.Defaults settings) {
        RestClientProperties.CircuitBreaker config = settings.circuitBreaker();
        if (!Boolean.TRUE.equals(config.enabled())) {
            return null;
        }
        return breakers.circuitBreaker(resilienceName(clientName), CircuitBreakerConfig.custom()
                .failureRateThreshold(config.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.slidingWindowSize())
                .minimumNumberOfCalls(config.minimumCalls())
                .waitDurationInOpenState(config.openDuration())
                .permittedNumberOfCallsInHalfOpenState(config.halfOpenCalls())
                .build());
    }

    private Bulkhead bulkhead(String clientName, RestClientProperties.Defaults settings) {
        RestClientProperties.Bulkhead config = settings.bulkhead();
        if (!Boolean.TRUE.equals(config.enabled())) {
            return null;
        }
        return bulkheads.bulkhead(resilienceName(clientName), BulkheadConfig.custom()
                .maxConcurrentCalls(config.maxConcurrent())
                .maxWaitDuration(config.maxWait())
                .build());
    }

    /** Apart from any Resilience4j instance of the same name an application configures itself. */
    private static String resilienceName(String clientName) {
        return "rest-" + clientName;
    }

    /** The registries, for metrics and health. */
    public CircuitBreakerRegistry circuitBreakers() {
        return breakers;
    }

    /** The registries, for metrics and health. */
    public BulkheadRegistry bulkheads() {
        return bulkheads;
    }

    /**
     * Sets who hears of each attempt.
     *
     * @param listener receives the client name and the attempt's outcome
     */
    public void onAttempt(BiConsumer<String, String> listener) {
        this.attempts = Objects.requireNonNull(listener, "listener must not be null");
    }

    /** Lets calls in flight finish briefly, then cuts them, so a stop is bounded. */
    @Override
    public void destroy() {
        httpClients.values().forEach(HttpClient::shutdown);
        for (HttpClient client : httpClients.values()) {
            try {
                if (!client.awaitTermination(CLOSE_WAIT)) {
                    client.shutdownNow();
                }
            } catch (InterruptedException e) {
                client.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Tags observations with the logical platform client name instead of the remote host. */
    private static final class ClientNameConvention extends DefaultClientRequestObservationConvention {

        private final KeyValue clientName;

        private ClientNameConvention(String clientName) {
            this.clientName = ClientHttpObservationDocumentation.LowCardinalityKeyNames.CLIENT_NAME.withValue(clientName);
        }

        @Override
        protected KeyValue clientName(ClientRequestObservationContext context) {
            return clientName;
        }
    }
}
