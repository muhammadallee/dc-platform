package ae.gov.dubaicustoms.platform.errors;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import org.apiguardian.api.API;

/**
 * Thrown when the request clashes with the current state of the resource (duplicate creation,
 * stale optimistic-locking version, …); maps to HTTP 409.
 *
 * <pre>{@code
 * if (repository.existsByName(name)) {
 *     throw new ConflictException(new ErrorCode("DC-ORDER-0409"), "order name already taken");
 * }
 * }</pre>
 *
 * <p>Thread-safe (immutable). Constructor arguments must not be {@code null}.
 *
 * @since 0.1.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public class ConflictException extends BusinessException {

    /**
     * Creates the exception.
     *
     * @param code the stable error code; never {@code null}
     * @param message the detail message, safe to expose to API clients; never {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is null
     */
    public ConflictException(ErrorCode code, String message) {
        super(code, message, HttpStatusHint.CONFLICT);
    }
}
