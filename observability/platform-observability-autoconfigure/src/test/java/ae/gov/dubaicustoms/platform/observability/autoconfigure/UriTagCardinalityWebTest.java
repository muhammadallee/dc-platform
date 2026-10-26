package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Phase-02 R5.2 / D106: the platform relies on Boot's uri-tag cap. Outbound calls built from literal
 * (non-templated) URIs are the realistic unbounded source — each id becomes a uri value — so 400
 * such calls must leave at most {@code max-uri-tags} (100) client series, while the templated server
 * side stays at one. This test fails if the cap is removed or disabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = UriTagCardinalityWebTest.App.class)
class UriTagCardinalityWebTest {

    private static final int CALLS = 400;

    @Autowired
    private Environment environment;

    @Autowired
    private RestClient.Builder builder;

    @Autowired
    private MeterRegistry registry;

    @Test
    void literalOutboundUrisAreCappedAndTemplatedServerUrisStayBounded() {
        RestClient client = builder.baseUrl("http://localhost:" + environment.getProperty("local.server.port")).build();
        for (int i = 0; i < CALLS; i++) {
            client.get().uri("/orders/" + i).retrieve().toBodilessEntity();
        }

        Set<String> clientUris = uris("http.client.requests");
        assertThat(clientUris).isNotEmpty().hasSizeLessThanOrEqualTo(100);
        assertThat(uris("http.server.requests")).contains("/orders/{id}").hasSizeLessThanOrEqualTo(2);
    }

    private Set<String> uris(String meter) {
        return registry.find(meter).meters().stream()
                .map(Meter::getId)
                .map(id -> id.getTag("uri"))
                .collect(Collectors.toSet());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Orders.class)
    static class App {
    }

    @RestController
    static class Orders {

        @GetMapping("/orders/{id}")
        String order(@PathVariable String id) {
            return id;
        }
    }
}
