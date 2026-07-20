package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Default {@link PlatformRestClientFactory}: JDK HttpClient request factory plus platform conventions. */
public final class DefaultPlatformRestClientFactory implements PlatformRestClientFactory {

    /** Matches the default header name in {@code platform-core-autoconfigure}'s CoreProperties. */
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final RestClientProperties properties;
    private final List<PlatformRestClientCustomizer> customizers;

    public DefaultPlatformRestClientFactory(RestClientProperties properties, List<PlatformRestClientCustomizer> customizers) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.customizers = List.copyOf(customizers);
    }

    @Override
    public RestClient.Builder builder(String clientName) {
        Objects.requireNonNull(clientName, "clientName must not be null");
        RestClientProperties.Defaults timeouts = properties.timeoutsFor(clientName);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(timeouts.connectTimeout())
                        .withReadTimeout(timeouts.readTimeout()));

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (properties.propagateCorrelation()) {
            builder.requestInterceptor((request, body, execution) -> {
                RequestContext.correlationId().ifPresent(id -> request.getHeaders().set(CORRELATION_HEADER, id.value()));
                return execution.execute(request, body);
            });
        }
        builder.defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (request, response) -> {
            String body = readBody(response.getBody());
            String remoteCorrelationId = response.getHeaders().getFirst(CORRELATION_HEADER);
            throw new RemoteCallException(clientName, response.getStatusCode().value(), body, remoteCorrelationId, null);
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
}
