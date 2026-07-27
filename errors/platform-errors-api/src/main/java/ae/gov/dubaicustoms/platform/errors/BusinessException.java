package ae.gov.dubaicustoms.platform.errors;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Thrown for business-rule violations that map to HTTP 4xx problem responses.
 *
 * <p>Carries an {@link HttpStatusHint} (default {@link HttpStatusHint#UNPROCESSABLE 422}) that the
 * platform exception handler turns into the response status; the {@link ErrorCode} becomes the
 * problem {@code type}/{@code title} and the machine-readable {@code code} extension.
 *
 * <pre>{@code
 * if (order.isShipped()) {
 *     throw new BusinessException(new ErrorCode("DC-ORDER-0021"), "shipped orders cannot change");
 * }
 * }</pre>
 *
 * <p>Thread-safe (immutable). Constructor arguments must not be {@code null}.
 *
 * @since 0.1.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public class BusinessException extends PlatformException {

    private final HttpStatusHint statusHint;

    /**
     * Creates the exception with the default {@link HttpStatusHint#UNPROCESSABLE} hint.
     *
     * @param code the stable error code; never {@code null}
     * @param message the detail message, safe to expose to API clients; never {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is null
     */
    public BusinessException(ErrorCode code, String message) {
        this(code, message, HttpStatusHint.UNPROCESSABLE);
    }

    /**
     * Creates the exception with an explicit status hint (for subclasses such as
     * {@link NotFoundException}).
     *
     * @param code the stable error code; never {@code null}
     * @param message the detail message, safe to expose to API clients; never {@code null}
     * @param statusHint the HTTP status to map to; never {@code null}
     * @throws NullPointerException if any argument is null
     */
    protected BusinessException(ErrorCode code, String message, HttpStatusHint statusHint) {
        super(code, message);
        this.statusHint = Objects.requireNonNull(statusHint, "statusHint must not be null");
    }

    /**
     * Returns the HTTP status this violation maps to.
     *
     * @return the status hint; never {@code null}, {@link HttpStatusHint#UNPROCESSABLE} by default
     */
    public HttpStatusHint statusHint() {
        return statusHint;
    }
}
