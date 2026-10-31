package ae.gov.dubaicustoms.platform.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Round-trip test against a real broker. Excluded from the default build (docker JUnit tag). */
@Tag("docker")
@Testcontainers
class RabbitEventTransportIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private RabbitEventTransport transport;
    private CachingConnectionFactory connectionFactory;

    @AfterEach
    void closeTransport() {
        if (transport != null) {
            transport.close();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private void startTransport() {
        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        var rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(100), false,
                Duration.ofSeconds(5));
    }

    @Test
    void aMessageNoQueueIsBoundForFailsInsteadOfBeingDropped() {
        startTransport();
        // Declares the exchange and binds the queue to another routing key.
        transport.subscribe("dc.unrouted:bound.key", "test-group", (key, value, headers) -> { });

        assertThatThrownBy(() -> transport.send("dc.unrouted:other.key", null, "hello".getBytes(StandardCharsets.UTF_8), Map.of()))
                .isInstanceOf(EventPublishException.class).hasMessageContaining("unroutable");
    }

    @Test
    void aMissingExchangeFailsTheSend() {
        startTransport();

        assertThatThrownBy(() -> transport.send("dc.no-such-exchange", null, "hello".getBytes(StandardCharsets.UTF_8), Map.of()))
                .isInstanceOf(EventPublishException.class);
    }

    @Test
    void publishesAndDeliversARoundTrip() {
        startTransport();

        var received = new CopyOnWriteArrayList<String>();
        transport.subscribe("dc.orders:order.placed", "test-group",
                (key, value, headers) -> received.add(new String(value, StandardCharsets.UTF_8)));

        transport.send("dc.orders:order.placed", null, "hello".getBytes(StandardCharsets.UTF_8),
                Map.of("eventType", "OrderPlaced"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly("hello"));
    }
}
