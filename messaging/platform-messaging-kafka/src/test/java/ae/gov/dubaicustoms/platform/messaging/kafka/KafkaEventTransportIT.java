package ae.gov.dubaicustoms.platform.messaging.kafka;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/** Round-trip test against a real broker. Excluded from the default build (docker JUnit tag). */
@Tag("docker")
@Testcontainers
class KafkaEventTransportIT {

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    private KafkaEventTransport transport;

    @AfterEach
    void closeTransport() {
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    void publishesAndDeliversARoundTrip() {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer.class);
        var kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(producerProps));

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var consumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(consumerProps);

        transport = new KafkaEventTransport(kafkaTemplate, consumerFactory, 3, Duration.ofMillis(100));

        var received = new CopyOnWriteArrayList<String>();
        transport.subscribe("dc.orders", "test-group",
                (key, value, headers) -> received.add(new String(value, StandardCharsets.UTF_8)));

        transport.send("dc.orders", null, "hello".getBytes(StandardCharsets.UTF_8), Map.of("eventType", "OrderPlaced"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(received).containsExactly("hello"));
    }
}
