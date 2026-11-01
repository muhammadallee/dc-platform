package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ae.gov.dubaicustoms.platform.messaging.EventHandler;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.EventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.JacksonEventSerializer;
import ae.gov.dubaicustoms.platform.messaging.rabbit.RabbitEventTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** What a failing handler does on a real broker: how many attempts, and where the message ends up. */
@Tag("docker")
@Testcontainers
class RabbitHandlerFailureIT {

    private static final String DESTINATION = "dc.failure:order";
    private static final String QUEUE = "dc.failure.order.app";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private final AtomicInteger attempts = new AtomicInteger();
    private CachingConnectionFactory connections;
    private RabbitEventTransport transport;
    private EventHandlerRegistrar registrar;

    /** The handler under test. */
    public class FailingHandler {

        @EventHandler(destination = DESTINATION)
        public void on(String payload) {
            attempts.incrementAndGet();
            throw new IllegalStateException("handler failure");
        }
    }

    @AfterEach
    void close() {
        registrar.destroy();
        transport.close();
        connections.destroy();
    }

    @Test
    void aFailingHandlerRunsExactlyMaxAttemptsThenTheBrokerDeadLettersIt() {
        connections = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connections.setUsername(RABBIT.getAdminUsername());
        connections.setPassword(RABBIT.getAdminPassword());
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        var template = new RabbitTemplate(connections);
        template.setMandatory(true);
        transport = new RabbitEventTransport(template, connections, true, Duration.ofSeconds(5), 10, Duration.ofSeconds(5), 5);
        var properties = new MessagingProperties(true, "dc.", new MessagingProperties.Handler(
                new MessagingProperties.Retry(3, Duration.ofMillis(50), Duration.ofMillis(200))), null, null, null);
        registrar = new EventHandlerRegistrar(transport, new JacksonEventSerializer(new ObjectMapper()), properties, "app", null);
        registrar.postProcessAfterInitialization(new FailingHandler(), "failing");
        registrar.afterSingletonsInstantiated();

        transport.send(DESTINATION, null, "\"poison\"".getBytes(), Map.of("eventType", "t"));

        var admin = new RabbitAdmin(connections);
        await().atMost(Duration.ofSeconds(30)).until(() -> messages(admin, QUEUE + ".dlq") > 0);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> attempts.get() == 3);
        assertThat(attempts).hasValue(3);
        assertThat(messages(admin, QUEUE + ".dlq")).isEqualTo(1);
        assertThat(messages(admin, QUEUE)).isZero();
    }

    private static int messages(RabbitAdmin admin, String queue) {
        Properties properties = admin.getQueueProperties(queue);
        return properties == null ? -1 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }
}
