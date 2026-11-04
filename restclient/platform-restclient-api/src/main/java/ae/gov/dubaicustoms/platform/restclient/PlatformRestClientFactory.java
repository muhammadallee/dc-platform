package ae.gov.dubaicustoms.platform.restclient;

import org.apiguardian.api.API;
import org.springframework.web.client.RestClient;

/**
 * Factory for platform-conventional {@link RestClient.Builder}s. Inject this instead of
 * {@link RestClient.Builder} directly.
 *
 * <p>Conventions applied by the returned builder: W3C {@code traceparent} propagation, auth token
 * relay when a security provider is present, {@code Observation} instrumentation, timeouts and an overall
 * deadline, retries of idempotent methods only, a circuit breaker and a bulkhead per client name (all
 * overridable), and every failure surfaced as a {@link RemoteCallException}. Replacing the request factory
 * through the returned builder removes the retries, deadline, breaker and bulkhead.
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
 * {@code clientName} is never {@code null}, must not be blank, and should come from a fixed
 * set: it names a breaker, a bulkhead and metric tags.
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
