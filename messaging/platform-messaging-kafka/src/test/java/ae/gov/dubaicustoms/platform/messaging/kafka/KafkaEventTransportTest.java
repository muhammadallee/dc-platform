package ae.gov.dubaicustoms.platform.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Docker-free unit tests: mock KafkaTemplate wiring, no broker required. */
class KafkaEventTransportTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final ConsumerFactory<String, byte[]> consumerFactory = mock(ConsumerFactory.class);

    @Test
    void nameIsKafka() {
        var transport = new KafkaEventTransport(kafkaTemplate, consumerFactory, 3, Duration.ofMillis(50));

        assertThat(transport.name()).isEqualTo("kafka");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendBuildsProducerRecordWithKeyAndHeadersThenBlocksForAck() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var transport = new KafkaEventTransport(kafkaTemplate, consumerFactory, 3, Duration.ofMillis(50));

        transport.send("dc.orders", "order-1".getBytes(), "payload".getBytes(),
                Map.of("eventType", "OrderPlaced"));

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, byte[]> sent = captor.getValue();
        assertThat(sent.topic()).isEqualTo("dc.orders");
        assertThat(sent.key()).isEqualTo("order-1");
        assertThat(sent.value()).isEqualTo("payload".getBytes());
        Header header = sent.headers().lastHeader("eventType");
        assertThat(header).isNotNull();
        assertThat(new String(header.value())).isEqualTo("OrderPlaced");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendWrapsExecutionExceptionFromFailedAck() {
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);
        var transport = new KafkaEventTransport(kafkaTemplate, consumerFactory, 3, Duration.ofMillis(50));

        assertThatThrownBy(() -> transport.send("dc.orders", null, "v".getBytes(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dc.orders");
    }

    @Test
    void deliverPassesKeyValueAndHeadersToListener() {
        var headers = new RecordHeaders();
        headers.add(new RecordHeader("eventType", "OrderPlaced".getBytes()));
        var record = new ConsumerRecord<>("dc.orders", 0, 0L, "order-1", "payload".getBytes());
        record.headers().add(new RecordHeader("eventType", "OrderPlaced".getBytes()));
        List<Object[]> delivered = new CopyOnWriteArrayList<>();

        KafkaEventTransport.deliver(record, (key, value, receivedHeaders) ->
                delivered.add(new Object[] {key, value, receivedHeaders}));

        assertThat(delivered).hasSize(1);
        assertThat(new String((byte[]) delivered.get(0)[0])).isEqualTo("order-1");
        assertThat((byte[]) delivered.get(0)[1]).isEqualTo("payload".getBytes());
        @SuppressWarnings("unchecked")
        Map<String, String> receivedHeaders = (Map<String, String>) delivered.get(0)[2];
        assertThat(receivedHeaders).containsEntry("eventType", "OrderPlaced");
    }

    @Test
    void deliverPassesNullKeyThrough() {
        var record = new ConsumerRecord<String, byte[]>("dc.orders", 0, 0L, null, "payload".getBytes());
        List<Object[]> delivered = new CopyOnWriteArrayList<>();

        KafkaEventTransport.deliver(record, (key, value, receivedHeaders) -> delivered.add(new Object[] {key}));

        assertThat(delivered.get(0)[0]).isNull();
    }

    @Test
    void fromKafkaHeadersConvertsEveryHeader() {
        var record = new ConsumerRecord<String, byte[]>("dc.orders", 0, 0L, "k", "v".getBytes());
        record.headers().add(new RecordHeader("eventType", "OrderPlaced".getBytes()));
        record.headers().add(new RecordHeader("eventVersion", "2".getBytes()));

        Map<String, String> headers = KafkaEventTransport.fromKafkaHeaders(record);

        assertThat(headers).containsEntry("eventType", "OrderPlaced").containsEntry("eventVersion", "2");
    }

    @Test
    void subscribeStartsAContainerAndCloseStopsIt() throws Exception {
        Map<String, Object> consumerProps = new HashMap<>();
        // Unreachable on purpose: container.start()/stop() don't require a live connection.
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var realConsumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(consumerProps);
        var transport = new KafkaEventTransport(kafkaTemplate, realConsumerFactory, 3, Duration.ofMillis(10));

        var subscription = transport.subscribe("dc.orders", "test-group", (key, value, headers) -> { });
        subscription.close();
        transport.close();
    }
}
