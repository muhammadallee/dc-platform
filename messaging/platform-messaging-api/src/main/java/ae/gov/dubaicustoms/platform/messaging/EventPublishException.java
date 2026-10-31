package ae.gov.dubaicustoms.platform.messaging;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Thrown when {@link EventPublisher#publish} fails: the transport rejected the send or did not
 * acknowledge it.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class EventPublishException extends PlatformException {

    private static final ErrorCode CODE = new ErrorCode("DC-MSG-0001");

    private final boolean rejected;

    /**
     * Creates the exception.
     *
     * @param message the detail message; never {@code null}
     * @param cause the underlying transport failure, or {@code null}
     * @throws NullPointerException if {@code message} is null
     */
    public EventPublishException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message; never {@code null}
     * @param cause the underlying transport failure, or {@code null}
     * @param rejected whether the sink received this message and refused it, see {@link #rejected()}
     * @throws NullPointerException if {@code message} is null
     */
    public EventPublishException(String message, Throwable cause, boolean rejected) {
        super(CODE, Objects.requireNonNull(message, "message must not be null"), cause);
        this.rejected = rejected;
    }

    /**
     * Whether the sink received this message and refused it (for example, unroutable), so sending it again may never
     * work. {@code false} for an outage or a timeout, which time can fix.
     *
     * @return {@code true} if the sink refused the message
     */
    public boolean rejected() {
        return rejected;
    }
}
