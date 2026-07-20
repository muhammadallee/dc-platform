package ae.gov.dubaicustoms.platform.messaging;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;

/**
 * Thrown when {@link EventPublisher#publish} fails: the transport rejected the send or did not
 * acknowledge it.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 0.2.0
 */
public final class EventPublishException extends PlatformException {

    private static final ErrorCode CODE = new ErrorCode("DC-MSG-0001");

    /**
     * Creates the exception.
     *
     * @param message the detail message; never {@code null}
     * @param cause the underlying transport failure, or {@code null}
     * @throws NullPointerException if {@code message} is null
     */
    public EventPublishException(String message, Throwable cause) {
        super(CODE, Objects.requireNonNull(message, "message must not be null"), cause);
    }
}
