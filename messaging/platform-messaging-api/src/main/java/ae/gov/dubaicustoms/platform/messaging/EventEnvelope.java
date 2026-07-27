package ae.gov.dubaicustoms.platform.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * An integration event envelope carrying a payload plus transport-agnostic metadata. Immutable.
 *
 * <p>Headers always include {@code correlationId}, {@code eventType}, and {@code eventVersion};
 * {@code platform-messaging-autoconfigure}'s {@code EventPublisher} implementation adds the first
 * from the current request context and the latter two from this envelope before handing off to
 * the transport. Additional headers are transport- or application-specific.
 *
 * <pre>{@code
 * EventEnvelope<OrderPlaced> event = EventEnvelope.of(new OrderPlaced(orderId))
 *         .key(orderId)
 *         .build();
 * publisher.publish("dc.orders", event);
 * }</pre>
 *
 * <p>Thread-safe (immutable); {@code headers} is an unmodifiable snapshot.
 *
 * @param eventType the logical event type, derived from {@link EventType#value()} or the
 *     payload's simple class name if absent; never {@code null}
 * @param eventVersion the schema version, from {@link EventType#version()} or {@code 1}
 * @param key the partitioning/routing key, or {@code null} if the transport doesn't need one
 * @param payload the event payload; never {@code null}
 * @param headers transport-agnostic metadata; never {@code null}, unmodifiable
 * @param occurredAt when the event occurred; never {@code null}
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public record EventEnvelope<T>(String eventType, int eventVersion, String key, T payload,
                                Map<String, String> headers, Instant occurredAt) {

    /**
     * Validates required components and defensively copies {@code headers}.
     *
     * @throws NullPointerException if {@code eventType}, {@code payload}, {@code headers}, or
     *     {@code occurredAt} is null
     */
    public EventEnvelope {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        headers = Map.copyOf(headers);
    }

    /**
     * Starts a builder wrapping {@code payload}. The event type and version are read from
     * {@link EventType @EventType} on the payload's class if present, otherwise the type falls
     * back to the class's simple name and the version to {@code 1}.
     *
     * @param payload the event payload; never {@code null}
     * @return a new builder; never {@code null}
     */
    public static <T> Builder<T> of(T payload) {
        return new Builder<>(payload);
    }

    /**
     * Builds {@link EventEnvelope} instances. Not thread-safe; confine to one thread (a single
     * publish call).
     *
     * @since 0.2.0
     */
    public static final class Builder<T> {

        private final T payload;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String key;
        private Instant occurredAt;

        private Builder(T payload) {
            this.payload = Objects.requireNonNull(payload, "payload must not be null");
        }

        /**
         * Sets the partitioning/routing key.
         *
         * @param key the key, or {@code null} for transports that don't need one
         * @return this builder
         */
        public Builder<T> key(String key) {
            this.key = key;
            return this;
        }

        /**
         * Adds a header. Later calls with the same {@code name} overwrite earlier ones.
         *
         * @param name the header name; never {@code null}
         * @param value the header value
         * @return this builder
         */
        public Builder<T> header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name must not be null"), value);
            return this;
        }

        /**
         * Sets the occurrence instant explicitly (useful for deterministic tests); defaults to
         * {@link Instant#now()} at {@link #build()} time if never called.
         *
         * @param occurredAt the occurrence instant; never {@code null}
         * @return this builder
         */
        public Builder<T> occurredAt(Instant occurredAt) {
            this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            return this;
        }

        /**
         * Builds the envelope.
         *
         * @return a new, immutable envelope; never {@code null}
         */
        public EventEnvelope<T> build() {
            EventType annotation = payload.getClass().getAnnotation(EventType.class);
            String type = annotation != null ? annotation.value() : payload.getClass().getSimpleName();
            int version = annotation != null ? annotation.version() : 1;
            // No Clock injected here: this is a value-object builder, not platform logic: callers
            // needing deterministic timestamps call occurredAt(clock.instant()) explicitly.
            Instant instant = occurredAt != null ? occurredAt : Instant.now();
            return new EventEnvelope<>(type, version, key, payload, headers, instant);
        }
    }
}
