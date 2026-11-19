package ae.gov.dubaicustoms.platform.messaging;

import java.util.Objects;
import org.apiguardian.api.API;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when {@link EventPublisher#publish} fails: the transport rejected the send or did not
 * acknowledge it.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class EventPublishException extends ErrorResponseException {

    private static final String CODE = "DC-MSG-0001";

    private final String message;

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
        super(HttpStatus.INTERNAL_SERVER_ERROR, problem(), cause);
        this.message = Objects.requireNonNull(message, "message must not be null");
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

    /**
     * Returns the detail message given at construction (never the error body).
     *
     * @return the message; never {@code null}
     */
    @Override
    public String getMessage() {
        return message;
    }

    // A 500 with the stable code; the platform's error handler replaces the detail with its generic text.
    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setProperty("code", CODE);
        return problem;
    }
}
