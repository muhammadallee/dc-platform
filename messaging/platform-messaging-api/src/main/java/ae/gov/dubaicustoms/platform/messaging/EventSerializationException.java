package ae.gov.dubaicustoms.platform.messaging;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Thrown when an {@code EventSerializer} fails to serialize a payload for publish, or deserialize
 * received bytes into a handler's parameter type.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class EventSerializationException extends PlatformException {

    private static final ErrorCode CODE = new ErrorCode("DC-MSG-0002");

    /**
     * Creates the exception.
     *
     * @param message the detail message; never {@code null}
     * @param cause the underlying (de)serialization failure, or {@code null}
     * @throws NullPointerException if {@code message} is null
     */
    public EventSerializationException(String message, Throwable cause) {
        super(CODE, Objects.requireNonNull(message, "message must not be null"), cause);
    }
}
