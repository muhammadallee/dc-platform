package ae.gov.dubaicustoms.platform.restclient;

import org.apiguardian.api.API;
import org.springframework.web.client.RestClient;

/**
 * Factory for platform-conventional {@link RestClient.Builder}s. Inject this instead of
 * {@link RestClient.Builder} directly.
 *
 * <p>Conventions applied by the returned builder: correlation header propagation, auth token
 * relay when a security provider is present, {@code Observation} instrumentation, sane default
 * timeouts (per-client overridable), and error responses mapped to {@link RemoteCallException}.
 *
 * <pre>{@code
 * @Service
 * class OrdersClient {
 *     private final RestClient client;
 *
 *     OrdersClient(PlatformRestClientFactory factory) {
 *         this.client = factory.builder("orders").baseUrl("https://orders.internal").build();
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe; a single factory instance is shared platform-wide.
 * {@code clientName} is never {@code null} and must not be blank.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public interface PlatformRestClientFactory {

    /**
     * Returns a new, pre-configured builder for the named client.
     *
     * @param clientName tags metrics and looks up the {@code dc.platform.restclient.clients.<name>.*}
     *     configuration overrides; never {@code null}
     * @return a fresh builder carrying platform conventions; never {@code null}
     */
    RestClient.Builder builder(String clientName);
}
