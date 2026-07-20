package ae.gov.dubaicustoms.platform.messaging.inmemory.internal;

import java.util.Map;

/**
 * One message in flight inside an in-memory queue, tracking redelivery attempts so far.
 *
 * <p>Internal: not part of the messaging contract; visible outside its package only for use by
 * {@code InMemoryEventTransport} in the parent package.
 */
public record QueuedMessage(byte[] key, byte[] value, Map<String, String> headers, int attempts) {

    /** Returns a copy with {@code attempts} incremented by one, for requeuing after a nack. */
    public QueuedMessage withAttemptIncremented() {
        return new QueuedMessage(key, value, headers, attempts + 1);
    }
}
