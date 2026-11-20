package ae.gov.dubaicustoms.platform.messaging;

import java.util.Objects;
import org.apiguardian.api.API;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when an {@code EventSerializer} fails to serialize a payload for publish, or deserialize
 * received bytes into a handler's parameter type.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class EventSerializationException extends ErrorResponseException {

    private static final String CODE = "DC-MSG-0002";

    private final String message;

    /**
     * Creates the exception.
     *
     * @param message the detail message; never {@code null}
     * @param cause the underlying (de)serialization failure, or {@code null}
     * @throws NullPointerException if {@code message} is null
     */
    public EventSerializationException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, problem(), cause);
        this.message = Objects.requireNonNull(message, "message must not be null");
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
