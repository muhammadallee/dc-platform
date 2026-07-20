package ae.gov.dubaicustoms.platform.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Docker-free unit tests: mock RabbitTemplate/ConnectionFactory wiring, no broker required. */
class RabbitEventTransportTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final ConnectionFactory connectionFactory = mockConnectionFactory();

    private static ConnectionFactory mockConnectionFactory() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        org.mockito.Mockito.when(factory.createConnection()).thenReturn(mock(Connection.class));
        return factory;
    }

    @Test
    void nameIsRabbit() {
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(50), false);

        assertThat(transport.name()).isEqualTo("rabbit");
    }

    @Test
    void sendUsesParsedExchangeAndRoutingKey() {
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(50), false);

        transport.send("dc.orders:order.placed", "order-1".getBytes(), "payload".getBytes(), Map.of("eventType", "OrderPlaced"));

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
        verify(rabbitTemplate).send(org.mockito.ArgumentMatchers.eq("dc.orders"),
                org.mockito.ArgumentMatchers.eq("order.placed"), any(Message.class));
        assertThat(messageCaptor.getValue().getBody()).isEqualTo("payload".getBytes());
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders()).containsEntry("eventType", "OrderPlaced");
    }

    @Test
    void sendWithoutRoutingKeyUsesEmptyRoutingKey() {
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(50), false);

        transport.send("dc.orders", null, "payload".getBytes(), Map.of());

        verify(rabbitTemplate).send("dc.orders", "", new Message("payload".getBytes(), new MessageProperties()));
    }

    @Test
    void deliverExtractsKeyValueAndHeadersExcludingTheKeyHeader() {
        var properties = new MessageProperties();
        properties.setHeader("key", "order-1".getBytes());
        properties.setHeader("eventType", "OrderPlaced");
        var message = new Message("payload".getBytes(), properties);
        List<Object[]> delivered = new CopyOnWriteArrayList<>();

        RabbitEventTransport.deliver(message, (key, value, headers) -> delivered.add(new Object[] {key, value, headers}));

        assertThat(delivered).hasSize(1);
        assertThat(new String((byte[]) delivered.get(0)[0])).isEqualTo("order-1");
        assertThat((byte[]) delivered.get(0)[1]).isEqualTo("payload".getBytes());
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) delivered.get(0)[2];
        assertThat(headers).containsEntry("eventType", "OrderPlaced").doesNotContainKey("key");
    }

    @Test
    void deliverHandlesAbsentKeyHeader() {
        var message = new Message("payload".getBytes(), new MessageProperties());
        List<Object[]> delivered = new CopyOnWriteArrayList<>();

        RabbitEventTransport.deliver(message, (key, value, headers) -> delivered.add(new Object[] {key}));

        assertThat(delivered.get(0)[0]).isNull();
    }

    @Test
    void fromRabbitHeadersConvertsEveryHeaderExceptKey() {
        var properties = new MessageProperties();
        properties.setHeader("key", "k".getBytes());
        properties.setHeader("eventType", "OrderPlaced");
        properties.setHeader("eventVersion", 2);
        var message = new Message("v".getBytes(), properties);

        Map<String, String> headers = RabbitEventTransport.fromRabbitHeaders(message);

        assertThat(headers).containsEntry("eventType", "OrderPlaced").containsEntry("eventVersion", "2")
                .doesNotContainKey("key");
    }

    @Test
    void subscribeDeclaresTopologyStartsContainerAndCloseStopsIt() throws Exception {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, rabbitAdmin, 3, Duration.ofMillis(10), false);

        var subscription = transport.subscribe("dc.orders:order.placed", "test-group", (key, value, headers) -> { });

        verify(rabbitAdmin, org.mockito.Mockito.times(2)).declareExchange(any());
        verify(rabbitAdmin, org.mockito.Mockito.times(2)).declareQueue(any());
        verify(rabbitAdmin, org.mockito.Mockito.times(2)).declareBinding(any());
        subscription.close();
        transport.close();
    }

    @Test
    void subscribeSupportsQuorumQueuesOptIn() throws Exception {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, rabbitAdmin, 3, Duration.ofMillis(10), true);

        var subscription = transport.subscribe("dc.orders", "test-group", (key, value, headers) -> { });
        subscription.close();
    }
}
