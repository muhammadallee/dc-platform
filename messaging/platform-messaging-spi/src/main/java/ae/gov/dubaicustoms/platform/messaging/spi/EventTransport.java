package ae.gov.dubaicustoms.platform.messaging.spi;

import java.util.Map;
import org.apiguardian.api.API;

/**
 * Provider contract for a messaging transport (in-memory, Kafka, RabbitMQ, ...).
 *
 * <p>{@code platform-messaging-autoconfigure} wraps exactly one {@code EventTransport} bean with
 * the {@code EventPublisher} implementation and the {@code EventHandlerRegistrar}; applications
 * never call this interface directly.
 *
 * <p><strong>Implementation requirements:</strong> implementations must be thread-safe and honor
 * at-least-once delivery: {@link #send} must not return normally until the underlying broker/queue
 * has accepted the message, and {@link Subscription} must keep redelivering an unacknowledged
 * message (per the platform's retry/DLQ policy, applied by the caller) rather than silently
 * dropping it. {@link #close} must release all resources (connections, dispatcher threads)
 * synchronously. Evolution note: new default methods may be added in minor versions; providers
 * should not assume this interface is closed for extension.
 *
 * @since 0.2.0
 */
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public interface EventTransport extends AutoCloseable {

    /**
     * Returns this transport's provider name, e.g. {@code "inmemory"}, {@code "kafka"},
     * {@code "rabbit"}. Used in {@code CapabilityDescriptor} detail and log messages.
     *
     * @return the provider name; never {@code null} or blank
     */
    String name();

    /**
     * Sends a message to {@code destination}, blocking until the transport acknowledges it.
     *
     * @param destination the transport-specific destination; never {@code null}
     * @param key the partitioning/routing key, or {@code null}
     * @param value the serialized payload; never {@code null}
     * @param headers transport-agnostic metadata to carry alongside the payload; never
     *     {@code null}
     */
    void send(String destination, byte[] key, byte[] value, Map<String, String> headers);

    /**
     * Subscribes {@code listener} to {@code destination} under the given consumer group.
     *
     * @param destination the transport-specific destination; never {@code null}
     * @param group the consumer group name, used for load-balancing across instances; never
     *     {@code null}
     * @param listener invoked for each message; never {@code null}
     * @return a handle to cancel the subscription; never {@code null}
     */
    Subscription subscribe(String destination, String group, TransportListener listener);

    /**
     * Cancels a subscription created by {@link #subscribe}.
     *
     * @since 0.2.0
     */
    interface Subscription extends AutoCloseable {
    }

    /**
     * Receives messages delivered by a subscription.
     *
     * @since 0.2.0
     */
    @FunctionalInterface
    interface TransportListener {

        /**
         * Handles one message.
         *
         * @param key the partitioning/routing key, or {@code null}
         * @param value the serialized payload; never {@code null}
         * @param headers transport-agnostic metadata carried with the message; never {@code null}
         * @throws RuntimeException to nack the message; the transport applies its redelivery
         *     policy. Returning normally acks the message.
         */
        void onMessage(byte[] key, byte[] value, Map<String, String> headers);
    }
}
