package ae.gov.dubaicustoms.platform.messaging.kafka;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;

/**
 * {@link EventTransport} over Apache Kafka. Built from Boot's own {@code KafkaTemplate} and
 * {@code ConsumerFactory} beans (produced from {@code spring.kafka.*} by Boot's own
 * {@code KafkaAutoConfiguration}) — broker connection details are never duplicated here, only
 * consumed. Destination = topic name.
 *
 * <p>Redelivery/DLQ is Kafka-native: each subscription's container is wrapped in a
 * {@link DefaultErrorHandler} backed by a {@link DeadLetterPublishingRecoverer} (publishes the
 * exhausted record to Kafka's default dead-letter topic, {@code <topic>.DLT}) with a
 * {@link FixedBackOff} derived from the platform's handler retry properties — this is in addition
 * to, not instead of, {@code platform-messaging-autoconfigure}'s transport-agnostic
 * republish-to-{@code dlq.suffix} fallback (which every provider gets for free).
 *
 * <p>Thread-safe.
 *
 * @since 0.2.0
 */
public final class KafkaEventTransport implements EventTransport {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ConsumerFactory<String, byte[]> consumerFactory;
    private final int maxAttempts;
    private final Duration backoff;
    private final CopyOnWriteArrayList<KafkaMessageListenerContainer<String, byte[]>> containers =
            new CopyOnWriteArrayList<>();

    /**
     * Creates the transport.
     *
     * @param kafkaTemplate the Boot-configured template used for both publish and DLQ recovery;
     *     never {@code null}
     * @param consumerFactory the Boot-configured consumer factory each subscription's container is
     *     built from; never {@code null}
     * @param maxAttempts total delivery attempts before a record is routed to the dead-letter
     *     topic; at least 1
     * @param backoff pause between redelivery attempts; never {@code null}
     */
    public KafkaEventTransport(KafkaTemplate<String, byte[]> kafkaTemplate, ConsumerFactory<String, byte[]> consumerFactory,
            int maxAttempts, Duration backoff) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory must not be null");
        this.maxAttempts = maxAttempts;
        this.backoff = Objects.requireNonNull(backoff, "backoff must not be null");
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        var record = new ProducerRecord<>(destination, null, keyString(key), value, toKafkaHeaders(headers));
        try {
            // .get() blocks for the broker ack, matching EventTransport.send's contract.
            kafkaTemplate.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing to " + destination, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("failed to publish to " + destination, e.getCause());
        }
    }

    @Override
    public Subscription subscribe(String destination, String group, TransportListener listener) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        ContainerProperties containerProperties = new ContainerProperties(destination);
        containerProperties.setGroupId(group);
        containerProperties.setMessageListener((MessageListener<String, byte[]>) record -> deliver(record, listener));

        var container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        int backOffAttempts = Math.max(0, maxAttempts - 1);
        container.setCommonErrorHandler(new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate), new FixedBackOff(backoff.toMillis(), backOffAttempts)));
        container.start();
        containers.add(container);
        return () -> {
            containers.remove(container);
            container.stop();
        };
    }

    // Package-private (not private): exercised directly by docker-free unit tests without needing
    // a live KafkaMessageListenerContainer poll loop.
    static void deliver(ConsumerRecord<String, byte[]> record, TransportListener listener) {
        byte[] key = record.key() != null ? record.key().getBytes(StandardCharsets.UTF_8) : null;
        listener.onMessage(key, record.value(), fromKafkaHeaders(record));
    }

    private static String keyString(byte[] key) {
        return key != null ? new String(key, StandardCharsets.UTF_8) : null;
    }

    private static Iterable<Header> toKafkaHeaders(Map<String, String> headers) {
        RecordHeaders kafkaHeaders = new RecordHeaders();
        headers.forEach((name, value) -> kafkaHeaders.add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8))));
        return kafkaHeaders;
    }

    static Map<String, String> fromKafkaHeaders(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        record.headers().forEach(header -> headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8)));
        return headers;
    }

    @Override
    public void close() {
        containers.forEach(KafkaMessageListenerContainer::stop);
        containers.clear();
    }
}
