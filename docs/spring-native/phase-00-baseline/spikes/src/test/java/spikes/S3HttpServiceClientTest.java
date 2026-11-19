package spikes;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * S3: Boot 4 HTTP service clients. A group's base URL comes from {@code spring.http.serviceclient.<group>.base-url},
 * and a {@code RestClientCustomizer} bean (how the platform would add timeouts, tracing, logging) reaches the
 * group's client. Expectation: the call reaches the local server with the customizer's header.
 */
@SpringBootTest(classes = S3HttpServiceClientTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class S3HttpServiceClientTest {

    static final AtomicReference<String> SEEN_HEADER = new AtomicReference<>();
    static final HttpServer SERVER = start();

    static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/orders/42", exchange -> {
                SEEN_HEADER.set(exchange.getRequestHeaders().getFirst("X-Platform"));
                byte[] body = "order-42".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.orders.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.http.serviceclient.orders.read-timeout", () -> "2s");
    }

    interface OrdersApi {
        @GetExchange("/orders/{id}")
        String get(@org.springframework.web.bind.annotation.PathVariable String id);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ImportHttpServices(group = "orders", types = OrdersApi.class)
    static class App {
        @Bean
        RestClientCustomizer platformHeader() {
            return builder -> builder.defaultHeader("X-Platform", "yes");
        }
    }

    @Autowired
    OrdersApi orders;

    @Test
    void groupPropertiesAndCustomizerApply() {
        assertThat(orders.get("42")).isEqualTo("order-42");
        assertThat(SEEN_HEADER.get()).isEqualTo("yes");
    }
}
