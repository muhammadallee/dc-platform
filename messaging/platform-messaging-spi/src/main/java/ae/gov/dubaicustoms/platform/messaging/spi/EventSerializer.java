package ae.gov.dubaicustoms.platform.messaging.spi;

import org.apiguardian.api.API;

/**
 * Pluggable payload (de)serialization for the messaging capability. Default implementation is
 * Jackson JSON, supplied by {@code platform-messaging-autoconfigure}
 * (@ConditionalOnMissingBean).
 *
 * <p><strong>Implementation requirements:</strong> must be thread-safe; {@link #serialize} and
 * {@link #deserialize} are called concurrently from publisher and handler-dispatch threads.
 * Failures must be reported as {@code EventSerializationException}, not swallowed.
 *
 * @since 0.2.0
 */
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public interface EventSerializer {

    /**
     * Serializes a payload to bytes.
     *
     * @param payload the payload; never {@code null}
     * @return the serialized bytes; never {@code null}
     * @throws ae.gov.dubaicustoms.platform.messaging.EventSerializationException if serialization
     *     fails
     */
    byte[] serialize(Object payload);

    /**
     * Deserializes bytes into an instance of {@code type}.
     *
     * @param bytes the serialized bytes; never {@code null}
     * @param type the target type; never {@code null}
     * @param <T> the target type
     * @return the deserialized instance; never {@code null}
     * @throws ae.gov.dubaicustoms.platform.messaging.EventSerializationException if deserialization
     *     fails
     */
    <T> T deserialize(byte[] bytes, Class<T> type);

    /**
     * Returns the MIME content type of the serialized form, e.g. {@code "application/json"}.
     * Carried as a header alongside published messages.
     *
     * @return the content type; never {@code null}
     */
    String contentType();
}
