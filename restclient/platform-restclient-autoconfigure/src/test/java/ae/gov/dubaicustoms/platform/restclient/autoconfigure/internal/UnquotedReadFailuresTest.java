package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HI-0099: a bare secret in a malformed success body reaches neither the message nor anything in the chain. */
class UnquotedReadFailuresTest {

    private static final String SECRET = "hunter2bareTokenValue";

    private HttpServer server;
    private volatile String body;
    private DefaultPlatformRestClientFactory factory;
    private RestClient client;

    record Shape(String name) {
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        factory = new DefaultPlatformRestClientFactory(new RestClientProperties(true, null, null), List.of(),
                () -> io.micrometer.observation.ObservationRegistry.NOOP);
        client = factory.builder("c").baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
    }

    @AfterEach
    void stop() throws Exception {
        factory.destroy();
        server.stop(0);
    }

    @Test
    void aBareTokenInAMalformedBodyIsNowhereInTheFailure() {
        body = "{\"name\": " + SECRET + "}";
        Throwable failure = catchThrowable(() -> client.get().retrieve().body(Shape.class));
        assertThat(failure).isInstanceOf(RestClientException.class);
        assertThat(printed(failure)).doesNotContain(SECRET);
    }

    @Test
    void aValueOfTheWrongTypeIsNowhereInTheFailure() {
        body = "{\"name\": {\"x\": \"" + SECRET + "\"}}";
        Throwable failure = catchThrowable(() -> client.get().retrieve().body(Shape.class));
        assertThat(failure).isInstanceOf(RestClientException.class);
        assertThat(printed(failure)).doesNotContain(SECRET);
    }

    @Test
    void aGenericTargetIsCoveredToo() {
        body = "[" + SECRET + "]";
        Throwable failure = catchThrowable(() -> client.get().retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, String>>>() { }));
        assertThat(failure).isInstanceOf(RestClientException.class);
        assertThat(printed(failure)).doesNotContain(SECRET);
    }

    @Test
    void aGoodBodyStillParses() {
        body = "{\"name\": \"ok\"}";
        assertThat(client.get().retrieve().body(Shape.class)).isEqualTo(new Shape("ok"));
    }

    private static String printed(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
