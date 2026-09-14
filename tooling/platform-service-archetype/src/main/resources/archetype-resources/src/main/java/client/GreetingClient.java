#set( $featureSet = ",${features}," )
## $d renders a literal dollar sign so the Spring placeholder below survives Velocity untouched.
#set( $d = '$' )
#if($featureSet.contains(",restclient,"))
package ${package}.client;

import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Feature sample (features=restclient): an outbound HTTP client built from
 * {@link PlatformRestClientFactory}. The platform pre-wires correlation-id propagation, bearer-token
 * relay for authenticated requests, per-client timeouts ({@code dc.platform.restclient.clients.greeting.*})
 * and maps non-2xx responses to {@code RemoteCallException} — never build a raw {@code RestTemplate}
 * or {@code WebClient}.
 *
 * <p>The base URL is mandatory configuration ({@code app.greeting.base-url}); the generated
 * {@code application.yml} sets a loopback placeholder outside the {@code prod} profile only, so a
 * production start without it fails fast. The built {@link RestClient} is thread-safe and shared.
 */
@Component
public class GreetingClient {

    private static final Logger log = LoggerFactory.getLogger(GreetingClient.class);

    private final RestClient client;

    public GreetingClient(PlatformRestClientFactory factory, @Value("${d}{app.greeting.base-url}") String baseUrl) {
        this.client = factory.builder("greeting").baseUrl(baseUrl).build();
    }

    /** {@code GET /greetings/{name}}; returns {@code null} when the remote answers with an empty body. */
    public Greeting fetch(String name) {
        log.info("Fetching remote greeting");
        return client.get()
                .uri("/greetings/{name}", name)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Greeting.class);
    }

    /** {@code POST /greetings} with a JSON body; any 2xx (including an empty 204) is success. */
    public void publish(Greeting greeting) {
        log.info("Publishing greeting");
        client.post()
                .uri("/greetings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(greeting)
                .retrieve()
                .toBodilessEntity();
    }

    /** Wire format of the remote greeting resource. */
    public record Greeting(String message) {
    }
}
#end
