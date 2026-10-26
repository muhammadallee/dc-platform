package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
@ExtendWith(OutputCaptureExtension.class)
class UriTagCardinalityWebTest {

    private static final int CALLS = 400;

    @Autowired
    private Environment environment;

    @Autowired
    private RestClient.Builder builder;

    @Autowired
    private MeterRegistry registry;

    @Test
    void literalOutboundUrisAreCappedAndTemplatedServerUrisStayBounded(CapturedOutput output) {
        RestClient client = builder.baseUrl("http://localhost:" + environment.getProperty("local.server.port")).build();
        for (int i = 0; i < CALLS; i++) {
            client.get().uri("/orders/" + i).retrieve().toBodilessEntity();
        }
        for (int i = 0; i < 50; i++) {
            // Unmatched server paths: one collapsed uri value, never the raw path.
            client.get().uri("/missing/" + i).retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity();
        }

        Set<String> clientUris = uris("http.client.requests");
        assertThat(clientUris).isNotEmpty().hasSizeLessThanOrEqualTo(100);
        Set<String> serverUris = uris("http.server.requests");
        assertThat(serverUris).contains("/orders/{id}").hasSizeLessThanOrEqualTo(3)
                .noneMatch(uri -> uri.startsWith("/missing/") || uri.matches("/orders/\\d+"));
        // Boot's deny filter warns once when the cap is reached, not once per denied series.
        assertThat(output.getOut().split("Reached the maximum number of", -1)).hasSize(2);
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
