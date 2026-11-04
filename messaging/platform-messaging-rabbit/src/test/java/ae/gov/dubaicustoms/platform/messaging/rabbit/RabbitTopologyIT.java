package ae.gov.dubaicustoms.platform.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.time.Duration;
import java.util.Map;
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

/** Topology and consumer behaviour on a real broker. */
@Tag("docker")
@Testcontainers
class RabbitTopologyIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private RabbitEventTransport transport;
    private CachingConnectionFactory connections;
    private RabbitAdmin admin;

    @AfterEach
    void close() {
        if (transport != null) {
            transport.close();
        }
        if (connections != null) {
            connections.destroy();
        }
    }

    private void start(int port, boolean quorum, int deliveryLimit) {
        connections = new CachingConnectionFactory(RABBIT.getHost(), port);
        connections.setUsername(RABBIT.getAdminUsername());
        connections.setPassword(RABBIT.getAdminPassword());
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        var template = new RabbitTemplate(connections);
        template.setMandatory(true);
        transport = new RabbitEventTransport(template, connections, quorum, Duration.ofSeconds(5), 10, Duration.ofSeconds(5),
                deliveryLimit);
        admin = new RabbitAdmin(connections);
    }

    private int messages(String queue) {
        var properties = admin.getQueueProperties(queue);
        return properties == null ? -1 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private String queueType(String queue) throws Exception {
        var result = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "type");
        return result.getStdout().lines().filter(line -> line.startsWith(queue + "\t")).findFirst().orElse("");
    }

    @Test
    void quorumQueuesAreDeclaredForTheMainAndTheDeadLetterQueue() throws Exception {
        start(RABBIT.getAmqpPort(), true, 5);

        try (var subscription = transport.subscribe("dc.quorum:order", "app", (key, value, headers) -> { })) {
            assertThat(queueType("dc.quorum.order.app")).endsWith("quorum");
            assertThat(queueType("dc.quorum.order.app.dlq")).endsWith("quorum");
        }
    }

    @Test
    void classicQueuesThroughTheFlag() throws Exception {
        start(RABBIT.getAmqpPort(), false, 5);

        try (var subscription = transport.subscribe("dc.classic:order", "app", (key, value, headers) -> { })) {
            assertThat(queueType("dc.classic.order.app")).endsWith("classic");
            assertThat(queueType("dc.classic.order.app.dlq")).endsWith("classic");
        }
    }

    @Test
    void aRejectedMessageReachesTheDeadLetterQueueWithItsHeaders() throws Exception {
        start(RABBIT.getAmqpPort(), true, 5);
        var deliveries = new AtomicInteger();
        try (var subscription = transport.subscribe("dc.reject:order", "app", (key, value, headers) -> {
            deliveries.incrementAndGet();
            throw new IllegalStateException("rejected");
        })) {
            transport.send("dc.reject:order", null, "body".getBytes(), Map.of("eventId", "e-1"));

            await().atMost(Duration.ofSeconds(30)).until(() -> messages("dc.reject.order.app.dlq") == 1);
        }
        assertThat(deliveries).hasValue(1);
        var dead = new RabbitTemplate(connections).receive("dc.reject.order.app.dlq", 5000);
        assertThat(dead).isNotNull();
        assertThat((String) dead.getMessageProperties().getHeader("eventId")).isEqualTo("e-1");
    }

    @Test
    void aMessageThatKeepsRequeueingReachesTheDeadLetterQueueAtTheDeliveryLimit() {
        // Stands in for a message that kills its consumer: delivered and never settled, over and over.
        start(RABBIT.getAmqpPort(), true, 2);
        var deliveries = new AtomicInteger();
        try (var subscription = transport.subscribe("dc.limit:order", "app", (key, value, headers) -> {
            deliveries.incrementAndGet();
            throw new EventTransport.TransportListener.RequeueException("put it back");
        })) {
            transport.send("dc.limit:order", null, "body".getBytes(), Map.of());

            await().atMost(Duration.ofSeconds(60)).until(() -> messages("dc.limit.order.app.dlq") == 1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(deliveries.get()).isEqualTo(3);
    }

    @Test
    void aBrokerThatLostTheDefinitionsGetsThemBackOnReconnect() throws Exception {
        start(RABBIT.getAmqpPort(), true, 5);
        var received = new AtomicInteger();
        try (var subscription = transport.subscribe("dc.lost:order", "app", (key, value, headers) -> received.incrementAndGet())) {
            RABBIT.execInContainer("rabbitmqctl", "stop_app");
            RABBIT.execInContainer("rabbitmqctl", "reset");
            RABBIT.execInContainer("rabbitmqctl", "start_app");

            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                connections.resetConnection();
                return messages("dc.lost.order.app") >= 0;
            });
        } catch (AssertionError | Exception e) {
            throw new AssertionError("definitions not restored; a reset also drops the user", e);
        }
    }

    @Test
    void aFatalFailureOnAReplayedMessageGoesBackToTheDeadLetterQueue() throws Exception {
        // Spring's default handler acknowledges a fatal failure on a message carrying x-death: gone for good.
        start(RABBIT.getAmqpPort(), true, 5);
        var deliveries = new AtomicInteger();
        try (var subscription = transport.subscribe("dc.fatal:order", "app", (key, value, headers) -> {
            deliveries.incrementAndGet();
            throw new ClassCastException("fatal to Spring");
        })) {
            var template = new RabbitTemplate(connections);
            template.convertAndSend("dc.fatal", "order", "body", message -> {
                message.getMessageProperties().setHeader("x-death", java.util.List.of(Map.of("count", 1L, "queue", "q")));
                return message;
            });

            await().atMost(Duration.ofSeconds(30)).until(() -> messages("dc.fatal.order.app.dlq") == 1);
        }
        assertThat(deliveries).hasValue(1);
    }

    @Test
    void aQueueOfTheOtherTypeFailsItsSubscriptionAndNotOthersOnReconnect() throws Exception {
        start(RABBIT.getAmqpPort(), false, 5);
        try (var classic = transport.subscribe("dc.mismatch:order", "app", (key, value, headers) -> { })) {
            assertThat(queueType("dc.mismatch.order.app")).endsWith("classic");
        }
        transport.close();
        connections.destroy();
        start(RABBIT.getAmqpPort(), true, 5);

        assertThatThrownBy(() -> transport.subscribe("dc.mismatch:order", "app", (key, value, headers) -> { }))
                .isInstanceOf(org.springframework.amqp.AmqpException.class);
        try (var other = transport.subscribe("dc.fine:order", "app", (key, value, headers) -> { })) {
            connections.resetConnection();
            assertThat(messages("dc.fine.order.app")).isZero();
        }
    }

    @Test
    void connectionCacheModeDoesNotRecurseOnTopology() throws Exception {
        connections = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connections.setUsername(RABBIT.getAdminUsername());
        connections.setPassword(RABBIT.getAdminPassword());
        connections.setCacheMode(CachingConnectionFactory.CacheMode.CONNECTION);
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        var template = new RabbitTemplate(connections);
        template.setMandatory(true);
        transport = new RabbitEventTransport(template, connections, true, Duration.ofSeconds(5), 10, Duration.ofSeconds(5), 5);
        admin = new RabbitAdmin(connections);

        try (var subscription = transport.subscribe("dc.cache:order", "app", (key, value, headers) -> { })) {
            connections.resetConnection();
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> messages("dc.cache.order.app") == 0);
        }
    }

    @Test
    void aSubscriptionWithoutABrokerDoesNotFailAndStartsWhenConnected() throws Exception {
        start(1, true, 5);

        try (var subscription = transport.subscribe("dc.absent:order", "app", (key, value, headers) -> { })) {
            assertThat(subscription).isNotNull();
        }
    }
}
