package ae.gov.dubaicustoms.platform.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Docker-free unit tests: mock RabbitTemplate/ConnectionFactory wiring, no broker required. */
class RabbitEventTransportTest {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofMillis(200);

    private final RabbitTemplate rabbitTemplate = mockTemplate();
    private final ConnectionFactory connectionFactory = mockConnectionFactory();

    private static RabbitTemplate mockTemplate() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(template.isMandatoryFor(any(Message.class))).thenReturn(true);
        confirmEverySend(template, new CorrelationData.Confirm(true, null), null);
        return template;
    }

    private static ConnectionFactory mockConnectionFactory() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(mock(Connection.class));
        when(factory.isPublisherConfirms()).thenReturn(true);
        when(factory.isPublisherReturns()).thenReturn(true);
        return factory;
    }

    /** Answers each confirmed send the way the broker does: any return first, then the confirm. */
    private static void confirmEverySend(RabbitTemplate template, CorrelationData.Confirm confirm, ReturnedMessage returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned != null) {
                correlation.setReturned(returned);
            }
            if (confirm != null) {
                correlation.getFuture().complete(confirm);
            }
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private RabbitEventTransport transport() {
        return new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(50), false, CONFIRM_TIMEOUT);
    }

    @Test
    void aSendReturnsOnlyAfterTheBrokerConfirms() {
        transport().send("dc.orders", null, "payload".getBytes(), Map.of());

        verify(rabbitTemplate).send(eq("dc.orders"), eq(""), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void aNackFromAClosingChannelFailsTheSendAsAnOutageNotARejection() {
        // Spring synthesizes this when a connection closes with confirms outstanding: it must never park a row.
        confirmEverySend(rabbitTemplate, new CorrelationData.Confirm(false, "Channel closed by application"), null);

        assertThatThrownBy(() -> transport().send("dc.orders", null, "payload".getBytes(), Map.of()))
                .isInstanceOfSatisfying(EventPublishException.class, e -> {
                    assertThat(e).hasMessageContaining("not confirmed");
                    assertThat(e.rejected()).isFalse();
                });
    }

    @Test
    void aMissingExchangeIsARejection() {
        confirmEverySend(rabbitTemplate, new CorrelationData.Confirm(false,
                "channel error; protocol method: #method<channel.close>(reply-code=404, reply-text=NOT_FOUND)"), null);

        assertThatThrownBy(() -> transport().send("dc.orders", null, "payload".getBytes(), Map.of()))
                .isInstanceOfSatisfying(EventPublishException.class, e -> assertThat(e.rejected()).isTrue());
    }

    @Test
    void aPublishThatBlocksIsBoundedByTheTimeout() {
        // A broker under a resource alarm blocks the publish itself, before any confirm can be awaited.
        org.mockito.Mockito.doAnswer(invocation -> {
            Thread.sleep(60_000);
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        long start = System.nanoTime();
        assertThatThrownBy(() -> transport().send("dc.orders", null, "payload".getBytes(), Map.of()))
                .isInstanceOf(EventPublishException.class).hasMessageContaining("within");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aBrokerThatIsDownFailsTheSendAsAnOutage() {
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> transport().send("dc.orders", null, "payload".getBytes(), Map.of()))
                .isInstanceOfSatisfying(EventPublishException.class, e -> assertThat(e.rejected()).isFalse());
    }

    @Test
    void aMandatoryExpressionFalseForThisMessageFailsTheSend() {
        var transport = transport();
        when(rabbitTemplate.isMandatoryFor(any(Message.class))).thenReturn(false);

        assertThatThrownBy(() -> transport.send("dc.orders", null, "payload".getBytes(), Map.of()))
                .isInstanceOf(EventPublishException.class).hasMessageContaining("not mandatory");
    }

    @Test
    void anUnroutableMessageFailsTheSendEvenThoughTheBrokerConfirmsIt() {
        // The broker acks a mandatory message it could not route, after returning it.
        var returned = new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "dc.orders", "");
        confirmEverySend(rabbitTemplate, new CorrelationData.Confirm(true, null), returned);

        assertThatThrownBy(() -> transport().send("dc.orders", null, "payload".getBytes(), Map.of()))
                .isInstanceOf(EventPublishException.class).hasMessageContaining("unroutable");
    }

    @Test
    void noConfirmWithinTheTimeoutFailsTheSend() {
        confirmEverySend(rabbitTemplate, null, null);

        assertThatThrownBy(() -> transport().send("dc.orders", null, "payload".getBytes(), Map.of()))
                .isInstanceOf(EventPublishException.class).hasMessageContaining("within");
    }

    @Test
    void aTransportWithoutConfirmsOrReturnsOrMandatoryRefusesToStart() {
        when(connectionFactory.isPublisherConfirms()).thenReturn(false);
        assertThatThrownBy(this::transport).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.publisher-confirm-type=correlated");

        when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        when(connectionFactory.isPublisherReturns()).thenReturn(false);
        assertThatThrownBy(this::transport).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.publisher-returns=true");

        when(connectionFactory.isPublisherReturns()).thenReturn(true);
        when(rabbitTemplate.isMandatoryFor(any(Message.class))).thenReturn(false);
        assertThatThrownBy(this::transport).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.template.mandatory=true");
    }

    @Test
    void nameIsRabbit() {
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(50), false, CONFIRM_TIMEOUT);

        assertThat(transport.name()).isEqualTo("rabbit");
    }

    @Test
    void sendUsesParsedExchangeAndRoutingKey() {
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(50), false, CONFIRM_TIMEOUT);

        transport.send("dc.orders:order.placed", "order-1".getBytes(), "payload".getBytes(), Map.of("eventType", "OrderPlaced"));

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("dc.orders"), eq("order.placed"), messageCaptor.capture(), any(CorrelationData.class));
        assertThat(messageCaptor.getValue().getBody()).isEqualTo("payload".getBytes());
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders()).containsEntry("eventType", "OrderPlaced");
    }

    @Test
    void sendWithoutRoutingKeyUsesEmptyRoutingKey() {
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, 3, Duration.ofMillis(50), false, CONFIRM_TIMEOUT);

        transport.send("dc.orders", null, "payload".getBytes(), Map.of());

        verify(rabbitTemplate).send(eq("dc.orders"), eq(""), eq(new Message("payload".getBytes(), new MessageProperties())),
                any(CorrelationData.class));
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
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, rabbitAdmin, 3, Duration.ofMillis(10), false, CONFIRM_TIMEOUT);

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
        var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, rabbitAdmin, 3, Duration.ofMillis(10), true, CONFIRM_TIMEOUT);

        var subscription = transport.subscribe("dc.orders", "test-group", (key, value, headers) -> { });
        subscription.close();
    }
}
