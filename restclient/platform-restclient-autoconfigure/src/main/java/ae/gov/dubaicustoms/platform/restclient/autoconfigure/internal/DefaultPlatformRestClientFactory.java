package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.web.client.RestClient;

/**
 * Default {@link PlatformRestClientFactory}: JDK HttpClient request factory plus platform conventions,
 * including {@code http.client.requests} observations tagged {@code client.name=<clientName>}.
 */
public final class DefaultPlatformRestClientFactory implements PlatformRestClientFactory {

    private final RestClientProperties properties;
    private final List<PlatformRestClientCustomizer> customizers;
    private final Supplier<ObservationRegistry> observationRegistry;

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
        RestClientProperties.Defaults timeouts = properties.timeoutsFor(clientName);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(timeouts.connectTimeout())
                        .withReadTimeout(timeouts.readTimeout()));

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry.get())
                .observationConvention(new ClientNameConvention(clientName));
        // No correlation interceptor: the observation registry above propagates traceparent, the
        // platform's only correlation id. No custom correlation header is sent or read.
        builder.defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (request, response) -> {
            String body = readBody(response.getBody());
            throw new RemoteCallException(clientName, response.getStatusCode().value(), body, null, null);
        });
        for (PlatformRestClientCustomizer customizer : customizers) {
            customizer.customize(clientName, builder);
        }
        return builder;
    }

    private static String readBody(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
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
